package com.slingring.net;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slingring.model.Decision;
import com.slingring.model.TransferRequest;
import com.slingring.model.TransferState;
import com.slingring.transfer.ReceiveService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The receiving side's HTTP endpoints, the only thing other devices can reach.
 *
 * GET /peer/info
 * POST /peer/request
 * PUT /peer/data/{id}
 * POST /peer/cancel/{id}
 *
 * Any other path is a 404 because no other context is registered.
 *
 */
public class PeerServer {

    private static final int MAX_JSON_BYTES = 16 * 1024;
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private final int requestedPort;
    private final String deviceId;
    private final String deviceName;
    private final ReceiveService receiveService;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private HttpServer server;
    private ExecutorService executor;

    /**
     * @param port 0 lets the OS choose a free port; read the real one with
     *             {@link #getPort()}
     */
    public PeerServer(int port, String deviceId, String deviceName, ReceiveService receiveService) {
        this.requestedPort = port;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.receiveService = receiveService;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(requestedPort), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.createContext("/peer/info", ex -> guarded(ex, "GET", this::info));
        server.createContext("/peer/request", ex -> guarded(ex, "POST", this::request));
        server.createContext("/peer/data/", ex -> guarded(ex, "PUT", this::data));
        server.createContext("/peer/cancel/", ex -> guarded(ex, "POST", this::cancel));

        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private void info(HttpExchange ex) throws IOException {
        send(ex, 200, new Wire.Info(deviceId, deviceName));
    }

    private void request(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readNBytes(MAX_JSON_BYTES + 1);
        if (body.length > MAX_JSON_BYTES) {
            throw new HttpError(413, "Request too large");
        }

        TransferRequest req;
        try {
            req = mapper.readValue(body, TransferRequest.class);
        } catch (IOException invalid) {
            throw new HttpError(400, "Invalid transfer request");
        }

        // Blocks until the user accepts/refuses or the consent window ends.
        Decision decision = receiveService.handleIncomingRequest(req, remoteIp(ex));
        send(ex, 200, new Wire.DecisionBody(decision));
    }

    private void data(HttpExchange ex) throws IOException {
        String id = idFrom(ex, "/peer/data/");
        long length = contentLength(ex);

        TransferState result;
        try {
            // Streams straight to a temp file in fixed-size buffers; never loads the file
            // into memory.
            result = receiveService.receiveData(id, ex.getRequestBody(), length, remoteIp(ex));
        } catch (NoSuchElementException unknown) {
            throw new HttpError(404, "Unknown transfer");
        } catch (IllegalStateException notAccepted) {
            throw new HttpError(409, "Transfer was not accepted");
        } catch (SecurityException wrongPeer) {
            throw new HttpError(403, "Not allowed");
        }
        send(ex, 200, new Wire.StateBody(result));
    }

    private void cancel(HttpExchange ex) throws IOException {
        String id = idFrom(ex, "/peer/cancel/");
        try {
            receiveService.cancel(id, remoteIp(ex));
        } catch (NoSuchElementException unknown) {
            throw new HttpError(404, "Unknown transfer");
        } catch (SecurityException wrongPeer) {
            throw new HttpError(403, "Not allowed");
        }
        ex.sendResponseHeaders(204, -1);
    }

    private interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    /**
     * Wraps every endpoint: checks the method, turns errors into JSON, always
     * closes the exchange.
     */
    private void guarded(HttpExchange ex, String method, Handler handler) {
        try (ex) {
            if (!method.equals(ex.getRequestMethod())) {
                throw new HttpError(405, "Method not allowed");
            }
            handler.handle(ex);
        } catch (HttpError e) {
            trySend(ex, e.status, new Wire.ErrorBody(e.getMessage()));
        } catch (Exception e) {
            System.err.println("PeerServer error on " + ex.getRequestURI().getPath() + ": " + e);
            trySend(ex, 500, new Wire.ErrorBody("Internal error"));
        }
    }

    private void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void trySend(HttpExchange ex, int status, Object body) {
        try {
            send(ex, status, body);
        } catch (Exception ignored) {
            // Response already started or the peer disconnected
        }
    }

    private static String idFrom(HttpExchange ex, String prefix) {
        String path = ex.getRequestURI().getPath();
        String id = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new HttpError(400, "Invalid transfer id");
        }
        return id;
    }

    private static long contentLength(HttpExchange ex) {
        try {
            long length = Long.parseLong(ex.getRequestHeaders().getFirst("Content-Length"));
            if (length < 0)
                throw new NumberFormatException();
            return length;
        } catch (NumberFormatException missingOrBad) {
            throw new HttpError(411, "Content-Length required");
        }
    }

    private static String remoteIp(HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    /** An error with the HTTP status to send back. */
    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}