package com.slingring.transfer;

import com.slingring.event.EventBus;
import com.slingring.model.Decision;
import com.slingring.model.TransferRequest;
import com.slingring.model.TransferSession;
import com.slingring.model.TransferState;
import com.slingring.util.ChecksumUtil;
import com.slingring.util.FileNames;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The receiving side of a transfer. It has two halves:
 *
 * Called by PeerServer (network threads):
 *   handleIncomingRequest  holds the sender's request open until the user decides
 *   receiveData            streams the body to a temp file, verifies it, moves it into place
 *   cancel(id, remoteIp)   the sender gave up
 *
 * Called by the UI (JavaFX thread; these return quickly):
 *   accept(id, saveDir) / refuse(id) / cancel(id)
 *
 * Safety rules enforced here:
 *   - Nothing is written until the user accepts and picks a folder.
 *   - Only the address that made the request may send the data or cancel it.
 *   - The body must be exactly the size announced in the request.
 *   - Data goes to a temp file in the save folder and is renamed only after the SHA-256 matches,
 *     so the folder never contains a half-written or corrupted file under its real name.
 *     Every failure path deletes the temp file.
 *   - File names from the sender are sanitized, and an existing file is never overwritten.
 *   - Limits on unanswered requests (per sender address and overall) stop prompt spam.
 */
public class ReceiveService {

    public static final Duration CONSENT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DATA_START_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_UNANSWERED_PER_IP = 3;
    private static final int MAX_UNANSWERED_TOTAL = 20;
    private static final long DISK_MARGIN_BYTES = 10L * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** One incoming transfer, from request to completion. */
    private static final class Incoming {
        final TransferSession session;
        final TransferRequest request;
        final String remoteIp;
        final CompletableFuture<Decision> decision = new CompletableFuture<>();
        final AtomicBoolean dataStarted = new AtomicBoolean();
        volatile Path saveDir;

        Incoming(TransferSession session, TransferRequest request, String remoteIp) {
            this.session = session;
            this.request = request;
            this.remoteIp = remoteIp;
        }
    }

    private final TransferSessionRegistry registry;
    private final EventBus eventBus;
    private final Map<String, Incoming> incoming = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "slingring-receive-watchdog");
        t.setDaemon(true);
        return t;
    });

    public ReceiveService(TransferSessionRegistry registry, EventBus eventBus) {
        this.registry = registry;
        this.eventBus = eventBus;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    // ================================================================== network side

    /**
     * Step 6. Shows the request to the user and blocks until they accept or refuse,
     * the sender cancels, or {@link #CONSENT_TIMEOUT} passes.
     * The UI learns about it through a TRANSFER_REQUEST event (payload: TransferSession.View).
     */
    public Decision handleIncomingRequest(TransferRequest request, String remoteIp) {
        if (tooManyUnanswered(remoteIp)) {
            return Decision.REFUSED; // quietly: no prompt for the user
        }

        String id = request.transferId();
        TransferSession session = TransferSession.forIncoming(request, FileNames.sanitize(request.fileName()));
        Incoming inc = new Incoming(session, request, remoteIp);
        if (incoming.putIfAbsent(id, inc) != null) {
            return Decision.REFUSED; // duplicate id
        }

        registry.register(session);
        eventBus.publish(EventBus.Types.TRANSFER_REQUEST, session.toView());

        Decision decision;
        try {
            decision = inc.decision.get(CONSENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Whoever completes the future first wins, so a click that lands exactly now still counts.
            inc.decision.complete(Decision.TIMEOUT);
            decision = inc.decision.getNow(Decision.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inc.decision.complete(Decision.REFUSED);
            decision = inc.decision.getNow(Decision.REFUSED);
        } catch (ExecutionException e) {
            decision = Decision.REFUSED; // not expected: nothing completes the future exceptionally
        }

        switch (decision) {
            case ACCEPTED -> scheduler.schedule(() -> expireIfNoData(inc),
                    DATA_START_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            case REFUSED -> {
                registry.update(session, TransferState.REFUSED, "Refused");
                incoming.remove(id, inc);
            }
            case TIMEOUT -> {
                registry.update(session, TransferState.FAILED,
                        "No response within " + CONSENT_TIMEOUT.toSeconds() + " seconds");
                incoming.remove(id, inc);
            }
        }
        return decision;
    }

    /**
     * Steps 7 and 8. Streams the body to a temp file, hashes the saved file, and either moves
     * it into place (INTACT) or deletes it (CORRUPTED). Returns the final state; the same
     * state is what the sender shows.
     *
     * @throws NoSuchElementException if the id is unknown (404)
     * @throws IllegalStateException  if the transfer was not accepted, or data already started (409)
     * @throws SecurityException      if the data comes from a different address than the request (403)
     */
    public TransferState receiveData(String id, InputStream body, long length, String remoteIp) {
        Incoming inc = incoming.get(id);
        if (inc == null) {
            throw new NoSuchElementException(id);
        }
        if (!inc.remoteIp.equals(remoteIp)) {
            throw new SecurityException("Data came from a different address than the request");
        }
        if (inc.decision.getNow(null) != Decision.ACCEPTED || inc.saveDir == null) {
            throw new IllegalStateException("Transfer was not accepted");
        }
        if (!inc.dataStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("Data already being received");
        }

        TransferSession session = inc.session;
        ProgressTracker progress = session.progress();
        Path temp = null;
        try {
            if (length != inc.request.size()) {
                registry.update(session, TransferState.FAILED, "File size differs from the request");
                return session.state();
            }
            if (!registry.update(session, TransferState.ACTIVE)) {
                return session.state(); // cancelled before it began
            }

            // Same folder as the destination, so the final rename never copies the file again.
            temp = Files.createTempFile(inc.saveDir, ".slingring-", ".part");
            long received = copyToFile(body, temp, length, session);
            if (received < length) {
                registry.update(session, TransferState.FAILED, "The connection ended before the file was complete");
                return session.state();
            }

            // 8. Hash the file as saved (not the stream), then compare with the sender's checksum.
            registry.update(session, TransferState.VERIFYING);
            progress.reset();
            String actual = ChecksumUtil.sha256(temp, bytes -> {
                progress.add(bytes);
                registry.progress(session);
            }, progress::isCancelled);

            if (ChecksumUtil.matches(inc.request.sha256(), actual)) {
                Path destination = moveIntoPlace(temp, inc.saveDir, session.fileName());
                temp = null; // moved; nothing left to delete
                registry.update(session, TransferState.INTACT, "Saved as " + destination.getFileName());
            } else {
                registry.update(session, TransferState.CORRUPTED, "Checksum did not match");
            }
            return session.state();

        } catch (CancellationException e) {
            registry.update(session, TransferState.CANCELLED, "Cancelled");
            return session.state();
        } catch (IOException e) {
            if (progress.isCancelled()) {
                registry.update(session, TransferState.CANCELLED, "Cancelled");
            } else {
                registry.update(session, TransferState.FAILED, describe(e));
            }
            return session.state();
        } catch (RuntimeException e) {
            registry.update(session, TransferState.FAILED, "Unexpected error: " + e.getMessage());
            return session.state();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            incoming.remove(id, inc);
        }
    }

    /** The sender cancelled (called by PeerServer). */
    public void cancel(String id, String remoteIp) {
        Incoming inc = incoming.get(id);
        if (inc == null) {
            throw new NoSuchElementException(id);
        }
        if (!inc.remoteIp.equals(remoteIp)) {
            throw new SecurityException("Cancel came from a different address than the request");
        }
        cancelInternal(inc, "The sender cancelled");
    }

    // ================================================================== UI side

    /**
     * The user accepted and chose a folder. Returns false if it is too late (timed out or
     * cancelled).
     *
     * @throws IOException if the folder is not writable or has too little free space; the request
     *                     stays open so the user can pick another folder
     */
    public boolean accept(String id, Path saveDir) throws IOException {
        Incoming inc = incoming.get(id);
        if (inc == null || inc.decision.isDone()) {
            return false;
        }
        if (!Files.isDirectory(saveDir) || !Files.isWritable(saveDir)) {
            throw new IOException("Cannot write to " + saveDir);
        }
        if (Files.getFileStore(saveDir).getUsableSpace() < inc.request.size() + DISK_MARGIN_BYTES) {
            throw new IOException("Not enough free space in " + saveDir);
        }
        inc.saveDir = saveDir;                       // set before completing, so receiveData sees it
        return inc.decision.complete(Decision.ACCEPTED);
    }

    /** The user refused. */
    public void refuse(String id) {
        Incoming inc = incoming.get(id);
        if (inc != null && inc.decision.complete(Decision.REFUSED)) {
            registry.update(inc.session, TransferState.REFUSED, "You refused");
        }
    }

    /** The user cancelled an accepted or running receive. */
    public void cancel(String id) {
        Incoming inc = incoming.get(id);
        if (inc != null) {
            cancelInternal(inc, "You cancelled");
        }
    }

    // ================================================================== helpers

    private void cancelInternal(Incoming inc, String detail) {
        registry.cancel(inc.session, detail);          // flips the flag, marks CANCELLED, records history
        inc.decision.complete(Decision.REFUSED);       // releases a /peer/request still waiting for an answer
        if (!inc.dataStarted.get()) {
            incoming.remove(inc.session.id(), inc);    // nothing is running; if data is streaming, its finally block cleans up
        }
    }

    private void expireIfNoData(Incoming inc) {
        if (!inc.dataStarted.get() && incoming.remove(inc.session.id(), inc)) {
            registry.update(inc.session, TransferState.FAILED, "The sender never started sending");
        }
    }

    private boolean tooManyUnanswered(String remoteIp) {
        int fromIp = 0;
        int total = 0;
        for (Incoming inc : incoming.values()) {
            if (!inc.decision.isDone()) {
                total++;
                if (inc.remoteIp.equals(remoteIp)) {
                    fromIp++;
                }
            }
        }
        return fromIp >= MAX_UNANSWERED_PER_IP || total >= MAX_UNANSWERED_TOTAL;
    }

    /**
     * Copies exactly {@code length} bytes (never more) from the request body into the file in
     * fixed-size chunks, checking the cancel flag between chunks. Returns how many bytes arrived;
     * fewer than {@code length} means the sender stopped early.
     *
     * A cancel takes effect at the next chunk. If the sender goes silent in the middle of a
     * read, it takes effect when data arrives or the connection drops.
     */
    private long copyToFile(InputStream in, Path target, long length, TransferSession session)
            throws IOException {
        ProgressTracker progress = session.progress();
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        try (OutputStream out = Files.newOutputStream(target)) {
            while (remaining > 0) {
                if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Cancelled");
                }
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n == -1) {
                    break;
                }
                out.write(buffer, 0, n);
                remaining -= n;
                progress.add(n);
                registry.progress(session);
            }
        }
        return length - remaining;
    }

    /**
     * Renames the verified temp file to its final name. Move without REPLACE_EXISTING, so if
     * another file appears between picking the name and moving, we pick the next free name
     * instead of overwriting anything.
     */
    private static Path moveIntoPlace(Path temp, Path dir, String safeName) throws IOException {
        for (int attempt = 0; attempt < 5; attempt++) {
            Path destination = FileNames.uniqueIn(dir, safeName);
            try {
                Files.move(temp, destination);
                return destination;
            } catch (FileAlreadyExistsException raced) {
                // taken in the meantime; try the next name
            }
        }
        throw new IOException("Could not find a free file name for " + safeName);
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? "Connection lost" : message;
    }
}
