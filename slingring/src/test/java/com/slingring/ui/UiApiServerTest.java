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
                "device-id", "test-device", null);
        server.start();

        URI stateUri = URI.create("http://127.0.0.1:" + server.getPort() + "/api/state");
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> unauthorized = client.send(
                HttpRequest.newBuilder(stateUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthorized.statusCode());

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

        URI uiUri = URI.create("http://127.0.0.1:" + server.getPort() + "/api/home");
        HttpResponse<String> ui = client.send(
                HttpRequest.newBuilder(uiUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ui.statusCode());
        assertTrue(ui.body().contains("<title>SlingRing | Local Transfer</title>"));
        assertTrue(ui.body().contains("const token = \"test-token\";"));
    }
}
