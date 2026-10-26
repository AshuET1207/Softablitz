package com.slingring.net;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slingring.model.Decision;
import com.slingring.model.Peer;
import com.slingring.model.TransferRequest;
import com.slingring.model.TransferState;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Talks to another device's PeerServer using the JDK's built-in HttpClient.
 */
public class PeerClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration QUICK_TIMEOUT = Duration.ofSeconds(5);
    /**
     * 120s wait + network delay
     */
    private static final Duration CONSENT_TIMEOUT = Duration.ofSeconds(135);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** ask the device at ip:port who it is. */
    public Peer fetchInfo(String ip, int port) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(ip, port) + "/peer/info"))
                .timeout(QUICK_TIMEOUT)
                .GET()
                .build();
        Wire.Info info = call(request, Wire.Info.class);
        if (info.id() == null || info.name() == null) {
            throw new IOException("That device did not answer like a SlingRing device");
        }
        return new Peer(info.id(), info.name(), ip, port);
    }

    /**
     * send the metadata (no file bytes) and wait for the user on the other
     * side to accept or refuse. Can take up to two minutes.
     */
    public Decision requestTransfer(Peer peer, TransferRequest request)
            throws IOException, InterruptedException {
        HttpRequest http = HttpRequest.newBuilder(URI.create(peer.baseUrl() + "/peer/request"))
                .timeout(CONSENT_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                .build();
        Wire.DecisionBody body = call(http, Wire.DecisionBody.class);
        if (body.decision() == null) {
            throw new IOException("Receiver sent no decision");
        }
        return body.decision();
    }

    /**
     * stream the file to the receiver and return the state it ended in
     * (INTACT, CORRUPTED, CANCELLED or FAILED).
     */
    public TransferState sendData(Peer peer, String transferId, long size,
            Supplier<InputStream> source)
            throws IOException, InterruptedException {
        // fromPublisher requires a positive length, so empty files use noBody
        // (Content-Length: 0).
        HttpRequest.BodyPublisher body = size > 0
                ? HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(source), size)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(peer.baseUrl() + "/peer/data/" + transferId))
                .header("Content-Type", "application/octet-stream")
                .PUT(body)
                .build();

        Wire.StateBody result = call(request, Wire.StateBody.class);
        if (result.state() == null) {
            throw new IOException("Receiver sent no result");
        }
        return result.state();
    }

    public boolean cancel(Peer peer, String transferId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create(peer.baseUrl() + "/peer/cancel/" + transferId))
                    .timeout(QUICK_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Helpers

    private <T> T call(HttpRequest request, Class<T> type) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(describeError(response));
        }
        return mapper.readValue(response.body(), type);
    }

    private String describeError(HttpResponse<byte[]> response) {
        try {
            Wire.ErrorBody error = mapper.readValue(response.body(), Wire.ErrorBody.class);
            if (error.error() != null) {
                return error.error() + " (HTTP " + response.statusCode() + ")";
            }
        } catch (IOException ignored) {
            // not JSON; fall through
        }
        return "Receiver returned HTTP " + response.statusCode();
    }

    /**
     * Reuses Peer's address formatting (IPv6 brackets) for an address we have not
     * turned into a Peer yet.
     */
    private static String baseUrl(String ip, int port) {
        return new Peer("unknown", "unknown", ip, port).baseUrl();
    }
}