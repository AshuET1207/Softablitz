package com.slingring.ui;

import com.slingring.discovery.PeerRegistry;
import com.slingring.event.EventBus;
import com.slingring.history.HistoryStore;
import com.slingring.net.PeerClient;
import com.slingring.transfer.ReceiveService;
import com.slingring.transfer.SendService;
import com.slingring.transfer.TransferSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiApiServerTest {

    @TempDir
    Path tempDir;

    private UiApiServer server;
    private ReceiveService receiveService;
    private ExecutorService workers;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
        if (receiveService != null) {
            receiveService.shutdown();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    @Test
    void apiRequiresTokenAndReturnsInitialState() throws Exception {
        EventBus eventBus = new EventBus();
        PeerRegistry peerRegistry = new PeerRegistry(eventBus);
        HistoryStore historyStore = new HistoryStore(tempDir.resolve("history.jsonl"));
        TransferSessionRegistry sessions = new TransferSessionRegistry(eventBus, historyStore);
        PeerClient peerClient = new PeerClient();
        workers = Executors.newSingleThreadExecutor();
        receiveService = new ReceiveService(sessions, eventBus);
        SendService sendService = new SendService("test-device", peerClient, sessions, workers);
        server = new UiApiServer("test-token", eventBus, peerRegistry, sessions,
                sendService, receiveService, peerClient, historyStore,
                "device-id", "test-device", () -> "");
        server.start();

        URI stateUri = URI.create("http://127.0.0.1:" + server.getPort() + "/api/state");
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> unauthorized = client.send(
                HttpRequest.newBuilder(stateUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> preflight = client.send(
                HttpRequest.newBuilder(stateUri)
                        .header("Origin", "null")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals("null", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

        HttpResponse<String> corsResponse = client.send(
                HttpRequest.newBuilder(stateUri)
                        .header("Origin", "null")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, corsResponse.statusCode());
        assertEquals("null", corsResponse.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

        HttpResponse<String> state = client.send(
                HttpRequest.newBuilder(stateUri)
                        .header("Authorization", "Bearer test-token")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, state.statusCode());
        assertTrue(state.body().contains("\"deviceId\":\"device-id\""));
        assertTrue(state.body().contains("\"peers\":[]"));
        assertTrue(state.body().contains("\"sessions\":[]"));
    }

    @Test
    void servesHtmlAndStaticAssets() throws Exception {
        EventBus eventBus = new EventBus();
        PeerRegistry peerRegistry = new PeerRegistry(eventBus);
        HistoryStore historyStore = new HistoryStore(tempDir.resolve("history.jsonl"));
        TransferSessionRegistry sessions = new TransferSessionRegistry(eventBus, historyStore);
        PeerClient peerClient = new PeerClient();
        workers = Executors.newSingleThreadExecutor();
        receiveService = new ReceiveService(sessions, eventBus);
        SendService sendService = new SendService("test-device", peerClient, sessions, workers);
        server = new UiApiServer("secret-token-123", eventBus, peerRegistry, sessions,
                sendService, receiveService, peerClient, historyStore,
                "device-id", "test-device", () -> "");
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.getPort();

        // 1. Root / serves index.html with injected token
        HttpResponse<String> rootResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, rootResp.statusCode());
        assertTrue(rootResp.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(rootResp.body().contains("\"secret-token-123\""));
        assertTrue(rootResp.body().contains("SLING"));

        // 2. /index.html also serves index.html
        HttpResponse<String> indexResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, indexResp.statusCode());

        // 3. /ui/css/app.css serves CSS
        HttpResponse<String> cssResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/ui/css/app.css")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cssResp.statusCode());
        assertTrue(cssResp.headers().firstValue("Content-Type").orElse("").contains("text/css"));
        assertTrue(cssResp.body().contains("--bg-base"));

        // 4. /ui/js/app.js serves JavaScript
        HttpResponse<String> jsResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/ui/js/app.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, jsResp.statusCode());
        assertTrue(jsResp.headers().firstValue("Content-Type").orElse("").contains("javascript"));
    }

    @Test
    void systemPathsAndValidationEndpoints() throws Exception {
        EventBus eventBus = new EventBus();
        PeerRegistry peerRegistry = new PeerRegistry(eventBus);
        HistoryStore historyStore = new HistoryStore(tempDir.resolve("history.jsonl"));
        TransferSessionRegistry sessions = new TransferSessionRegistry(eventBus, historyStore);
        PeerClient peerClient = new PeerClient();
        workers = Executors.newSingleThreadExecutor();
        receiveService = new ReceiveService(sessions, eventBus);
        SendService sendService = new SendService("test-device", peerClient, sessions, workers);
        server = new UiApiServer("test-token", eventBus, peerRegistry, sessions,
                sendService, receiveService, peerClient, historyStore,
                "device-id", "test-device", () -> "");
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.getPort();

        // System paths
        HttpResponse<String> pathsResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/system/paths"))
                        .header("Authorization", "Bearer test-token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, pathsResp.statusCode());
        assertTrue(pathsResp.body().contains("\"home\":"));

        // Validate dir
        HttpResponse<String> dirResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/validate/dir"))
                        .header("Authorization", "Bearer test-token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"" + tempDir.toAbsolutePath().toString().replace("\\", "\\\\") + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, dirResp.statusCode());
        assertTrue(dirResp.body().contains("\"valid\":true"));

        // Validate file
        java.nio.file.Path testFile = tempDir.resolve("sample.txt");
        java.nio.file.Files.writeString(testFile, "hello slingring");
        HttpResponse<String> fileResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/validate/file"))
                        .header("Authorization", "Bearer test-token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"" + testFile.toAbsolutePath().toString().replace("\\", "\\\\") + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, fileResp.statusCode());
        assertTrue(fileResp.body().contains("\"valid\":true"));
        assertTrue(fileResp.body().contains("\"name\":\"sample.txt\""));
        assertTrue(fileResp.body().contains("\"size\":15"));
    }
}
