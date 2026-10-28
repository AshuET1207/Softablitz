package com.slingring;


import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slingring.discovery.DiscoveryService;
import com.slingring.discovery.PeerRegistry;
import com.slingring.event.EventBus;
import com.slingring.history.HistoryStore;
import com.slingring.net.PeerClient;
import com.slingring.net.PeerServer;
import com.slingring.transfer.ReceiveService;
import com.slingring.transfer.SendService;
import com.slingring.transfer.TransferSessionRegistry;
import com.slingring.ui.UiApiServer;

/**
 * Headless entry point: the whole SlingRing backend with no window.
 * An Electron app (or any other front end) starts this jar as a child process.
 *
 * Responsibilities:
 *  1. Build every service once (composition root), in dependency order.
 *  2. Start the peer HTTP server (the only part other devices can reach) and mDNS discovery.
 *  3. Tell the parent process it is ready, with one JSON line on stdout.
 *  4. Shut everything down cleanly, including when the parent process dies.
 *
 * The local UI API binds to loopback and is authenticated with a per-process token.
 */
public class App {

    private static final int DEFAULT_PEER_PORT = 8765;
    private static final String EXIT_ON_STDIN_CLOSE = "--exit-on-stdin-close";

    // Pure ASCII output, so the ready line survives whatever charset the pipe uses.
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build());

    private final Path appDir;
    private final String deviceId = UUID.randomUUID().toString();
    private final String deviceName = resolveDeviceName();

    private ExecutorService executor;
    private EventBus eventBus;
    private PeerRegistry peerRegistry;
    private HistoryStore historyStore;
    private TransferSessionRegistry sessionRegistry;
    private PeerClient peerClient;
    private SendService sendService;
    private ReceiveService receiveService;
    private PeerServer peerServer;
    private DiscoveryService discoveryService;
    private UiApiServer uiApiServer;
    private final String uiToken = UUID.randomUUID().toString();

    /** Set if mDNS could not start, so a UI that connects later can still show the warning. */
    private volatile String discoveryError;
    private boolean stopped;

    public App(Path appDir) {
        this.appDir = appDir;
    }

    // ================================================================== lifecycle

    /** Builds and starts everything. Throws if the app cannot run at all (for example, no port). */
    public void start() throws IOException {
        Files.createDirectories(appDir);

        // Shared pool for sends, receives and hashing. Daemon threads, so they never keep the JVM alive.
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "slingring-worker");
            t.setDaemon(true);
            return t;
        });

        // ---- Core services (order matters: dependencies first) ----
        eventBus = new EventBus();
        peerRegistry = new PeerRegistry(eventBus);
        historyStore = new HistoryStore(appDir.resolve("history.jsonl"));
        sessionRegistry = new TransferSessionRegistry(eventBus, historyStore);

        peerClient = new PeerClient();
        sendService = new SendService(deviceName, peerClient, sessionRegistry, executor);
        receiveService = new ReceiveService(sessionRegistry, eventBus);

        // ---- Network: peer endpoints first, so the real port is known before we announce it ----
        peerServer = startPeerServer();

        // mDNS registration can take a moment, so keep it off the startup path.
        discoveryService = new DiscoveryService(deviceId, deviceName, peerServer.getPort(), peerRegistry);
        executor.submit(() -> {
            try {
                discoveryService.start();
            } catch (Exception e) {
                // Not fatal: the user can still add a device by IP address.
                discoveryError = String.valueOf(e.getMessage());
                System.err.println("Discovery failed: " + e);
                eventBus.publish(EventBus.Types.DISCOVERY_ERROR, discoveryError);
            }
        });

        uiApiServer = new UiApiServer(uiToken, eventBus, peerRegistry, sessionRegistry,
                sendService, receiveService, peerClient, historyStore, deviceId, deviceName, discoveryError);
        uiApiServer.start();
    }

    /** Try the default port so manual IP entry is predictable; fall back to any free port. */
    private PeerServer startPeerServer() throws IOException {
        try {
            PeerServer server = new PeerServer(DEFAULT_PEER_PORT, deviceId, deviceName, receiveService);
            server.start();
            return server;
        } catch (BindException busy) {
            PeerServer server = new PeerServer(0, deviceId, deviceName, receiveService); // 0 = OS picks a free port
            server.start();
            return server;
        }
    }

    /** Stop in reverse order of startup. Safe to call more than once. */
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;

        if (uiApiServer != null) {
            try { uiApiServer.stop(); } catch (Exception ignored) { }
        }
        if (discoveryService != null) {
            try { discoveryService.stop(); } catch (Exception ignored) { }
        }
        if (peerServer != null) {
            try { peerServer.stop(); } catch (Exception ignored) { }
        }
        if (receiveService != null) {
            receiveService.shutdown();
        }
        if (executor != null) {
            executor.shutdownNow(); // interrupts in-flight sends; their finally blocks clean up
        }
        if (eventBus != null) {
            eventBus.clear();
        }
    }

    /** Values reported to the parent process in the ready line. */
    private Map<String, Object> readyInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("ready", true);
        info.put("deviceId", deviceId);
        info.put("deviceName", deviceName);
        info.put("peerPort", peerServer.getPort());
        info.put("uiPort", uiApiServer.getPort());
        info.put("uiToken", uiToken);
        return info;
    }

    // ================================================================== main

    public static void main(String[] args) throws InterruptedException {
        App app = new App(resolveHome());

        try {
            app.start();
        } catch (Exception e) {
            e.printStackTrace(System.err);
            app.stop();
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("ready", false);
            failure.put("error", String.valueOf(e.getMessage()));
            emit(failure);
            System.exit(1);
            return;
        }

        // Runs on normal exit, Ctrl+C and SIGTERM. Not on SIGKILL, which nothing can catch.
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "slingring-shutdown"));

        if (Arrays.asList(args).contains(EXIT_ON_STDIN_CLOSE)) {
            watchParent();
        }

        emit(app.readyInfo());

        // Keep the main thread parked; the servers run on their own threads.
        new CountDownLatch(1).await();
    }

    /** Exit when the parent closes our stdin, which happens if it quits or crashes. */
    private static void watchParent() {
        Thread watcher = new Thread(() -> {
            try {
                while (System.in.read() != -1) {
                    // ignore anything the parent sends
                }
            } catch (IOException ignored) {
                // treat a broken pipe like end of file
            }
            System.exit(0); // runs the shutdown hook
        }, "slingring-parent-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** The one place that writes to stdout. */
    private static void emit(Map<String, Object> line) {
        try {
            System.out.println(JSON.writeValueAsString(line));
            System.out.flush();
        } catch (IOException e) {
            System.err.println("Could not write status line: " + e);
        }
    }

    private static Path resolveHome() {
        String override = System.getenv("SLINGRING_HOME");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".slingring");
    }

    private static String resolveDeviceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getProperty("user.name", "SlingRing-Device");
        }
    }
}