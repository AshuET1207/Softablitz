package com.slingring;

import com.slingring.discovery.DiscoveryService;
import com.slingring.discovery.PeerRegistry;
import com.slingring.event.EventBus;
import com.slingring.net.PeerClient;
import com.slingring.net.PeerServer;
import com.slingring.history.HistoryStore;
import com.slingring.transfer.ReceiveService;
import com.slingring.transfer.SendService;
import com.slingring.transfer.TransferSessionRegistry;
import com.slingring.ui.MainController;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JavaFX entry point
 * 
 *  1. Build every service once (composition root)
 *  2. Start the peer HTTP server and mDNS discovery
 *  3. Load the main window and hand the services to its controller
 *  4. Shut everything down cleanly on exit
 */
public class App extends Application {

    private static final int DEFAULT_PORT = 8765;
    private static final String SERVICE_DIR = ".slingring";

    // Shared thread pool
    private ExecutorService executor;

    private EventBus eventBus;
    private PeerRegistry peerRegistry;
    private TransferSessionRegistry sessionRegistry;
    private HistoryStore historyStore;
    private PeerServer peerServer;
    private DiscoveryService discoveryService;

    @Override
    public void start(Stage stage) throws IOException {
        Path appDir = Path.of(System.getProperty("user.home"), SERVICE_DIR);
        Files.createDirectories(appDir);

        String deviceId = UUID.randomUUID().toString();
        String deviceName = resolveDeviceName();

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

        PeerClient peerClient = new PeerClient();
        SendService sendService = new SendService(deviceName, peerClient, sessionRegistry, executor);
        ReceiveService receiveService = new ReceiveService(sessionRegistry, eventBus);

        try {
            peerServer = startPeerServer(deviceId, deviceName, receiveService);
        } catch (IOException e) {
            showFatal("Could not start the receiving server", e);
            return;
        }
        int port = peerServer.getPort();

        discoveryService = new DiscoveryService(deviceId, deviceName, port, peerRegistry);
        executor.submit(() -> {
            try {
                discoveryService.start();
            } catch (Exception e) {
                Platform.runLater(() -> eventBus.publish("discovery-error", e.getMessage()));
            }
        });

        // UI Loading
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/com/ghostcoders/slingring/ui/main.fxml"));
        loader.setControllerFactory(type -> {
            if (type == MainController.class) {
                return new MainController(peerRegistry, sendService, receiveService,
                        sessionRegistry, eventBus, peerClient, deviceName, port);
            }
            throw new IllegalStateException("Unknown controller: " + type);
        });

        Scene scene = new Scene(loader.load(), 900, 600);
        scene.getStylesheets().add(
                getClass().getResource("/com/ghostcoders/slingring/ui/styles.css").toExternalForm());

        stage.setTitle("SlingRing - " + deviceName);
        stage.setScene(scene);
        stage.setMinWidth(700);
        stage.setMinHeight(450);
        stage.show();
    }

    private PeerServer startPeerServer(String deviceId, String deviceName,
                                       ReceiveService receiveService) throws IOException {
        try {
            PeerServer server = new PeerServer(DEFAULT_PORT, deviceId, deviceName, receiveService);
            server.start();
            return server;
        } catch (java.net.BindException busy) {
            PeerServer server = new PeerServer(0, deviceId, deviceName, receiveService); // 0 = OS picks a free port
            server.start();
            return server;
        }
    }

    @Override
    public void stop() {
        if (discoveryService != null) {
            try { discoveryService.stop(); } catch (Exception ignored) { }
        }
        if (peerServer != null) {
            try { peerServer.stop(); } catch (Exception ignored) { }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static String resolveDeviceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getProperty("user.name", "SlingRing-Device");
        }
    }

    private static void showFatal(String message, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message + ":\n" + e.getMessage());
        alert.setHeaderText("SlingRing failed to start");
        alert.showAndWait();
        Platform.exit();
    }
}