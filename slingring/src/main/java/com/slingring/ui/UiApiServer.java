package com.slingring.ui;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slingring.discovery.PeerRegistry;
import com.slingring.event.EventBus;
import com.slingring.history.HistoryStore;
import com.slingring.model.Peer;
import com.slingring.model.TransferSession;
import com.slingring.net.PeerClient;
import com.slingring.transfer.ReceiveService;
import com.slingring.transfer.SendService;
import com.slingring.transfer.TransferSessionRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.swing.JFileChooser;
import javax.swing.JFrame;

/**
 * Loopback-only, authenticated HTTP API for the desktop UI.
 *
 * GET / serves the local test console.
 * GET /api/state
 * GET /api/events (SSE)
 * POST /api/send {path, peerId}
 * POST /api/peers/manual {ip, port}
 * POST /api/transfers/{id}/accept {saveDir}
 * POST /api/transfers/{id}/refuse
 * POST /api/transfers/{id}/cancel
 * GET /api/history
 */
public class UiApiServer {

    private static final int MAX_JSON_BYTES = 16 * 1024;
    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int MAX_HISTORY_LIMIT = 500;
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private final String token;
    private final EventBus eventBus;
    private final PeerRegistry peerRegistry;
    private final TransferSessionRegistry sessionRegistry;
    private final SendService sendService;
    private final ReceiveService receiveService;
    private final PeerClient peerClient;
    private final HistoryStore historyStore;
    private final String deviceId;
    private final String deviceName;
    private final Supplier<String> discoveryError;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private HttpServer server;
    private ExecutorService executor;

    public UiApiServer(String token, EventBus eventBus, PeerRegistry peerRegistry,
            TransferSessionRegistry sessionRegistry, SendService sendService,
            ReceiveService receiveService, PeerClient peerClient,
            HistoryStore historyStore, String deviceId, String deviceName,
            Supplier<String> discoveryError) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("UI API token must not be blank");
        }
        this.token = token;
        this.eventBus = eventBus;
        this.peerRegistry = peerRegistry;
        this.sessionRegistry = sessionRegistry;
        this.sendService = sendService;
        this.receiveService = receiveService;
        this.peerClient = peerClient;
        this.historyStore = historyStore;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.discoveryError = Objects.requireNonNull(discoveryError);
    }

    public void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("UI API server is started");
        }
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.createContext("/api", this::handle);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public int getPort() {
        if (server == null) {
            throw new IllegalStateException("UI API server is not started");
        }
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            try {
                String path = exchange.getRequestURI().getPath();
                if (!hostAllowed(exchange))
                    throw new HttpError(403, "Forbidden");
                allowElectronFileOrigin(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (("/api/home".equals(path) || "/index.html".equals(path) || "/".equals(path))
                        && "GET".equals(exchange.getRequestMethod())) {
                    ui(exchange);
                    return;
                }
                if ((path.startsWith("/ui/") || isStaticAssetPath(path))
                        && "GET".equals(exchange.getRequestMethod())) {
                    serveStatic(exchange, path);
                    return;
                }
                if (!authorized(exchange)) {
                    throw new HttpError(401, "Unauthorized");
                }
                String method = exchange.getRequestMethod();
                if ("/api/state".equals(path) && "GET".equals(method)) {
                    state(exchange);
                } else if ("/api/events".equals(path) && "GET".equals(method)) {
                    events(exchange);
                } else if ("/api/send".equals(path) && "POST".equals(method)) {
                    send(exchange);
                } else if ("/api/peers/manual".equals(path) && "POST".equals(method)) {
                    addPeer(exchange);
                } else if ("/api/history".equals(path) && "GET".equals(method)) {
                    history(exchange);
                } else if ("/api/system/paths".equals(path) && "GET".equals(method)) {
                    systemPaths(exchange);
                } else if ("/api/browse/file".equals(path) && "POST".equals(method)) {
                    browseFile(exchange);
                } else if ("/api/browse/dir".equals(path) && "POST".equals(method)) {
                    browseDir(exchange);
                } else if ("/api/validate/file".equals(path) && "POST".equals(method)) {
                    validateFile(exchange);
                } else if ("/api/validate/dir".equals(path) && "POST".equals(method)) {
                    validateDir(exchange);
                } else if (path.startsWith("/api/transfers/") && "POST".equals(method)) {
                    transferAction(exchange, path);
                } else if (path.startsWith("/api/") && isKnownPath(path)) {
                    throw new HttpError(405, "Method not allowed");
                } else {
                    throw new HttpError(404, "Not found");
                }
            } catch (HttpError e) {
                trySend(exchange, e.status, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                System.err.println("UiApiServer error on " + exchange.getRequestURI().getPath() + ": " + e);
                trySend(exchange, 500, Map.of("error", "Internal error"));
            }
        } catch (Exception e) {
            System.err.println("UiApiServer could not close exchange: " + e);
        }
    }

    private void state(HttpExchange exchange) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("deviceId", deviceId);
        state.put("deviceName", deviceName);
        state.put("discoveryError", discoveryError.get());
        state.put("peers", peerRegistry.all());
        state.put("sessions", sessionRegistry.views());
        send(exchange, 200, state);
    }

    private void events(HttpExchange exchange) throws IOException {
        BlockingQueue<String> pending = new ArrayBlockingQueue<>(256);
        AtomicBoolean overflow = new AtomicBoolean();
        EventBus.Subscription[] subscriptions = new EventBus.Subscription[] {
                subscribe(EventBus.Types.PEER_ADDED, "peer-added", pending, overflow),
                subscribe(EventBus.Types.PEER_REMOVED, "peer-removed", pending, overflow),
                subscribe(EventBus.Types.TRANSFER_REQUEST, "transfer-request", pending, overflow),
                subscribe(EventBus.Types.TRANSFER_UPDATE, "transfer-update", pending, overflow),
                subscribe(EventBus.Types.DISCOVERY_ERROR, "discovery-error", pending, overflow)
        };

        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream output = exchange.getResponseBody()) {
                while (!overflow.get() && !Thread.currentThread().isInterrupted()) {
                    String event;
                    try {
                        event = pending.poll(15, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (event == null) {
                        output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    } else {
                        output.write(event.getBytes(StandardCharsets.UTF_8));
                    }
                    output.flush();
                }
            }
        } catch (IOException disconnected) {
            // The browser closing an SSE connection is normal.
        } finally {
            for (EventBus.Subscription subscription : subscriptions) {
                subscription.close();
            }
        }
    }

    private void ui(HttpExchange exchange) throws IOException {
        byte[] page;
        try (var input = UiApiServer.class.getResourceAsStream("/ui/index.html")) {
            if (input == null) {
                throw new IOException("Missing UI resource: /ui/index.html");
            }
            String html = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__UI_TOKEN__", mapper.writeValueAsString(token));
            page = html.getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(page);
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith("/ui/")) {
            normalized = "/ui" + (normalized.startsWith("/") ? normalized : "/" + normalized);
        }
        if (normalized.contains("..")) {
            throw new HttpError(403, "Forbidden");
        }
        byte[] content;
        try (var in = UiApiServer.class.getResourceAsStream(normalized)) {
            if (in == null) {
                throw new HttpError(404, "Not found");
            }
            content = in.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", mimeTypeFor(normalized));
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static boolean isStaticAssetPath(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".css")
                || lower.endsWith(".js")
                || lower.endsWith(".svg")
                || lower.endsWith(".png")
                || lower.endsWith(".json")
                || lower.endsWith(".ico")
                || lower.endsWith(".woff")
                || lower.endsWith(".woff2")
                || lower.endsWith(".ttf");
    }

    private static String mimeTypeFor(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".css"))
            return "text/css; charset=utf-8";
        if (lower.endsWith(".js"))
            return "application/javascript; charset=utf-8";
        if (lower.endsWith(".svg"))
            return "image/svg+xml; charset=utf-8";
        if (lower.endsWith(".png"))
            return "image/png";
        if (lower.endsWith(".json"))
            return "application/json; charset=utf-8";
        if (lower.endsWith(".ico"))
            return "image/x-icon";
        if (lower.endsWith(".html"))
            return "text/html; charset=utf-8";
        if (lower.endsWith(".woff2"))
            return "font/woff2";
        if (lower.endsWith(".woff"))
            return "font/woff";
        if (lower.endsWith(".ttf"))
            return "font/ttf";
        return "application/octet-stream";
    }

    private void systemPaths(HttpExchange exchange) throws IOException {
        String home = System.getProperty("user.home", "");
        Path homePath = Path.of(home);
        Path downloads = homePath.resolve("Downloads");
        Path desktop = homePath.resolve("Desktop");
        Path documents = homePath.resolve("Documents");

        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("home", home);
        paths.put("downloads", Files.exists(downloads) ? downloads.toAbsolutePath().toString() : home);
        paths.put("desktop", Files.exists(desktop) ? desktop.toAbsolutePath().toString() : home);
        paths.put("documents", Files.exists(documents) ? documents.toAbsolutePath().toString() : home);
        send(exchange, 200, paths);
    }

    private void validateFile(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String rawPath = body.has("path") ? body.get("path").asText("") : "";
        if (rawPath.isBlank()) {
            send(exchange, 200, Map.of("valid", false, "error", "Path is empty"));
            return;
        }
        try {
            Path p = Path.of(rawPath);
            if (!Files.exists(p)) {
                send(exchange, 200, Map.of("valid", false, "error", "File does not exist"));
                return;
            }
            if (!Files.isRegularFile(p)) {
                send(exchange, 200, Map.of("valid", false, "error", "Target is not a regular file"));
                return;
            }
            if (!Files.isReadable(p)) {
                send(exchange, 200, Map.of("valid", false, "error", "File is not readable"));
                return;
            }
            long size = Files.size(p);
            String name = p.getFileName() != null ? p.getFileName().toString() : rawPath;
            send(exchange, 200, Map.of(
                    "valid", true,
                    "path", p.toAbsolutePath().toString(),
                    "name", name,
                    "size", size));
        } catch (Exception e) {
            send(exchange, 200,
                    Map.of("valid", false, "error", e.getMessage() == null ? "Invalid path" : e.getMessage()));
        }
    }

    private void validateDir(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String rawPath = body.has("path") ? body.get("path").asText("") : "";
        if (rawPath.isBlank()) {
            send(exchange, 200, Map.of("valid", false, "error", "Path is empty"));
            return;
        }
        try {
            Path p = Path.of(rawPath);
            if (!Files.exists(p)) {
                send(exchange, 200, Map.of("valid", false, "error", "Folder does not exist"));
                return;
            }
            if (!Files.isDirectory(p)) {
                send(exchange, 200, Map.of("valid", false, "error", "Target is not a folder"));
                return;
            }
            if (!Files.isWritable(p)) {
                send(exchange, 200, Map.of("valid", false, "error", "Folder is not writable"));
                return;
            }
            long usableSpace = Files.getFileStore(p).getUsableSpace();
            send(exchange, 200, Map.of(
                    "valid", true,
                    "path", p.toAbsolutePath().toString(),
                    "usableSpace", usableSpace));
        } catch (Exception e) {
            send(exchange, 200,
                    Map.of("valid", false, "error", e.getMessage() == null ? "Invalid folder path" : e.getMessage()));
        }
    }

    private void browseFile(HttpExchange exchange) throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            send(exchange, 200, Map.of("selected", false, "supported", false));
            return;
        }
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        EventQueue.invokeLater(() -> {
            try {
                Frame frame = new Frame();
                frame.setAlwaysOnTop(true);
                FileDialog fd = new FileDialog(frame, "Select File to Send", FileDialog.LOAD);
                fd.setVisible(true);
                String dir = fd.getDirectory();
                String file = fd.getFile();
                frame.dispose();
                if (dir != null && file != null) {
                    Path p = Path.of(dir, file);
                    if (Files.isRegularFile(p) && Files.isReadable(p)) {
                        future.complete(Map.of(
                                "selected", true,
                                "path", p.toAbsolutePath().toString(),
                                "name", p.getFileName().toString(),
                                "size", Files.size(p)));
                        return;
                    }
                }
                future.complete(Map.of("selected", false));
            } catch (Throwable t) {
                future.complete(Map.of("selected", false, "supported", false));
            }
        });
        try {
            Map<String, Object> result = future.get(120, TimeUnit.SECONDS);
            send(exchange, 200, result);
        } catch (Exception e) {
            send(exchange, 200, Map.of("selected", false));
        }
    }

    private void browseDir(HttpExchange exchange) throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            send(exchange, 200, Map.of("selected", false, "supported", false));
            return;
        }
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        EventQueue.invokeLater(() -> {
            JFrame dummyFrame = null;
            try {
                // 1. Create a dummy frame to host the file chooser on the OS window stack
                dummyFrame = new JFrame();
                dummyFrame.setAlwaysOnTop(true);
                dummyFrame.setLocationRelativeTo(null);

                // Required so the OS shifts active window focus to it immediately
                dummyFrame.setVisible(true);

                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select Destination Folder");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);

                // 2. Pass the dummy frame instead of null
                int res = chooser.showOpenDialog(dummyFrame);

                if (res == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    Path p = chooser.getSelectedFile().toPath();
                    if (Files.isDirectory(p) && Files.isWritable(p)) {
                        future.complete(Map.of(
                                "selected", true,
                                "path", p.toAbsolutePath().toString()));
                        return;
                    }
                }
                future.complete(Map.of("selected", false));
            } catch (Throwable t) {
                future.complete(Map.of("selected", false, "supported", false));
            } finally {
                // 3. Clean up the window resource to prevent system hanging or leakage
                if (dummyFrame != null) {
                    dummyFrame.dispose();
                }
            }
        });
        try {
            Map<String, Object> result = future.get(120, TimeUnit.SECONDS);
            send(exchange, 200, result);
        } catch (Exception e) {
            send(exchange, 200, Map.of("selected", false));
        }
    }

    private EventBus.Subscription subscribe(String type, String eventName, BlockingQueue<String> pending,
            AtomicBoolean overflow) {
        return eventBus.subscribe(type, payload -> {
            try {
                String event = "event: " + eventName + "\ndata: "
                        + mapper.writeValueAsString(payload) + "\n\n";
                if (!pending.offer(event)) {
                    overflow.set(true);
                }
            } catch (IOException serializationFailure) {
                throw new IllegalStateException("Could not serialize UI event " + eventName, serializationFailure);
            }
        });
    }

    private void send(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String path = requiredText(body, "path");
        String peerId = requiredText(body, "peerId");
        Peer peer = peerRegistry.find(peerId).orElseThrow(() -> new HttpError(404, "Unknown peer"));
        String id;
        try {
            id = sendService.sendAsync(Path.of(path), peer);
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw new HttpError(400, e.getMessage() == null ? "Invalid or unreadable file" : e.getMessage());
        }
        send(exchange, 202, Map.of("id", id));
    }

    private void addPeer(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String ip = requiredText(body, "ip");
        JsonNode portNode = body.get("port");
        if (portNode == null || !portNode.isIntegralNumber() || !portNode.canConvertToInt()) {
            throw new HttpError(400, "port must be an integer");
        }
        int port = portNode.intValue();
        if (port < 1 || port > 65535) {
            throw new HttpError(400, "port must be between 1 and 65535");
        }

        Peer peer;
        try {
            peer = peerClient.fetchInfo(ip, port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpError(503, "Peer lookup interrupted");
        } catch (IOException e) {
            throw new HttpError(502, e.getMessage() == null ? "Could not reach peer" : e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "Invalid peer address");
        }
        peerRegistry.add(peer);
        send(exchange, 200, peer);
    }

    private void transferAction(HttpExchange exchange, String path) throws IOException {
        String[] parts = path.substring("/api/transfers/".length()).split("/", -1);
        if (parts.length != 2 || !ID_PATTERN.matcher(parts[0]).matches()) {
            throw new HttpError(400, "Invalid transfer path");
        }
        String id = parts[0];
        TransferSession session = sessionRegistry.find(id)
                .orElseThrow(() -> new HttpError(404, "Unknown transfer"));

        switch (parts[1]) {
            case "accept" -> {
                if (session.direction() != TransferSession.Direction.RECEIVING) {
                    throw new HttpError(409, "Transfer is not incoming");
                }
                if (session.state().isTerminal()) {
                    throw new HttpError(409, "Transfer is no longer awaiting a decision");
                }
                String saveDir = requiredText(readJson(exchange), "saveDir");
                boolean accepted;
                try {
                    accepted = receiveService.accept(id, Path.of(saveDir));
                } catch (IOException | java.nio.file.InvalidPathException e) {
                    throw new HttpError(400, e.getMessage() == null ? "Invalid save directory" : e.getMessage());
                }
                if (!accepted) {
                    throw new HttpError(409, "Transfer is no longer awaiting a decision");
                }
                send(exchange, 204, null);
            }
            case "refuse" -> {
                if (session.direction() != TransferSession.Direction.RECEIVING) {
                    throw new HttpError(409, "Transfer is not incoming");
                }
                if (session.state().isTerminal()) {
                    throw new HttpError(409, "Transfer is no longer awaiting a decision");
                }
                receiveService.refuse(id);
                send(exchange, 204, null);
            }
            case "cancel" -> {
                if (session.direction() == TransferSession.Direction.SENDING) {
                    sendService.cancel(id);
                } else {
                    receiveService.cancel(id);
                }
                send(exchange, 204, null);
            }
            default -> throw new HttpError(404, "Not found");
        }
    }

    private void history(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        int limit = DEFAULT_HISTORY_LIMIT;
        if (query != null && !query.isBlank()) {
            for (String parameter : query.split("&")) {
                String[] pair = parameter.split("=", 2);
                if ("limit".equals(pair[0])) {
                    try {
                        limit = Integer.parseInt(pair.length == 2 ? pair[1] : "");
                    } catch (NumberFormatException e) {
                        throw new HttpError(400, "Invalid history limit");
                    }
                }
            }
        }
        if (limit < 1 || limit > MAX_HISTORY_LIMIT) {
            throw new HttpError(400, "History limit must be between 1 and " + MAX_HISTORY_LIMIT);
        }
        send(exchange, 200, historyStore.readRecent(limit));
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new HttpError(413, "Request too large");
        }
        try {
            JsonNode body = mapper.readTree(bytes);
            if (body == null || !body.isObject()) {
                throw new HttpError(400, "JSON object required");
            }
            return body;
        } catch (IOException invalid) {
            throw new HttpError(400, "Invalid JSON");
        }
    }

    private static String requiredText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new HttpError(400, name + " is required");
        }
        return value.textValue();
    }

    private boolean authorized(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }

    private boolean hostAllowed(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        int port = server.getAddress().getPort();
        return ("127.0.0.1:" + port).equals(host) || ("localhost:" + port).equals(host);
    }

    private void allowElectronFileOrigin(HttpExchange exchange) {
        if ("null".equals(exchange.getRequestHeaders().getFirst("Origin"))) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "null");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
    }

    private static boolean isKnownPath(String path) {
        return "/api/state".equals(path)
                || "/api/events".equals(path)
                || "/api/send".equals(path)
                || "/api/peers/manual".equals(path)
                || "/api/history".equals(path)
                || "/api/system/paths".equals(path)
                || "/api/browse/file".equals(path)
                || "/api/browse/dir".equals(path)
                || "/api/validate/file".equals(path)
                || "/api/validate/dir".equals(path)
                || path.startsWith("/api/transfers/");
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void trySend(HttpExchange exchange, int status, Object body) {
        try {
            send(exchange, status, body);
        } catch (IOException ignored) {
            // The client may have disconnected or the response may already be committed.
        }
    }

    private static final class HttpError extends RuntimeException {
        private final int status;

        private HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
