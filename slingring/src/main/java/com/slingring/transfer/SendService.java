package com.slingring.transfer;

import com.slingring.model.Decision;
import com.slingring.model.Peer;
import com.slingring.model.TransferRequest;
import com.slingring.model.TransferSession;
import com.slingring.model.TransferState;
import com.slingring.net.PeerClient;
import com.slingring.util.ChecksumUtil;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * The sending side of a transfer (steps 4 to 8 of the flow):
 *
 *   HASHING  compute the SHA-256 (progress is shown)
 *   PENDING  send the metadata only, then wait for the receiver to accept or refuse
 *   ACTIVE   stream the file straight from disk
 *   VERIFYING / INTACT / CORRUPTED   reported back by the receiver
 *
 * Everything runs on the shared executor; the UI thread only calls sendAsync() and cancel(),
 * which return immediately. The original file is read in place, so there is no staging copy.
 */
public class SendService {

    private final String senderName;
    private final PeerClient client;
    private final TransferSessionRegistry registry;
    private final ExecutorService executor;

    /** Who each running transfer is going to, so cancel() knows whom to tell. */
    private final Map<String, Peer> receivers = new ConcurrentHashMap<>();

    public SendService(String senderName, PeerClient client,
                       TransferSessionRegistry registry, ExecutorService executor) {
        this.senderName = senderName;
        this.client = client;
        this.registry = registry;
        this.executor = executor;
    }

    /**
     * Start sending a file. Returns the transfer id immediately; progress and the final
     * result arrive as TRANSFER_UPDATE events.
     *
     * @throws IOException if the file is missing, unreadable, or not a regular file
     */
    public String sendAsync(Path file, Peer peer) throws IOException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("Not a readable file: " + file);
        }
        String id = UUID.randomUUID().toString();
        TransferSession session = new TransferSession(
                id, file.getFileName().toString(), Files.size(file), "",
                peer.name(), TransferSession.Direction.SENDING, TransferState.HASHING);

        registry.register(session);
        receivers.put(id, peer);
        try {
            executor.submit(() -> run(session, file, peer));
        } catch (RejectedExecutionException shuttingDown) {
            receivers.remove(id);
            registry.update(session, TransferState.FAILED, "App is shutting down");
        }
        return id;
    }

    /** Cancel a running send from the UI. Returns immediately; the receiver is told in the background. */
    public void cancel(String id) {
        registry.find(id).ifPresent(session -> {
            if (session.direction() != TransferSession.Direction.SENDING || session.state().isTerminal()) {
                return;
            }
            registry.cancel(session, "You cancelled");
            Peer peer = receivers.get(id);
            if (peer != null) {
                try {
                    // A network call, so keep it off the UI thread. It also releases a receiver
                    // that is still showing the accept/refuse prompt.
                    executor.submit(() -> client.cancel(peer, id));
                } catch (RejectedExecutionException ignored) {
                    // shutting down
                }
            }
        });
    }

    // ------------------------------------------------------------------ worker

    private void run(TransferSession session, Path file, Peer peer) {
        ProgressTracker progress = session.progress();
        try {
            // 4. Hash (cancellable, with progress).
            String hash = ChecksumUtil.sha256(file, bytes -> {
                progress.add(bytes);
                registry.progress(session);
            }, progress::isCancelled);

            if (progress.bytesTransferred() != session.size()) {
                registry.update(session, TransferState.FAILED, "The file changed while it was being read");
                return;
            }
            session.setSha256(hash);
            progress.reset();

            // 5. Request: metadata only, no file bytes yet.
            if (!registry.update(session, TransferState.PENDING)) {
                return; // cancelled while hashing
            }
            TransferRequest request = new TransferRequest(
                    session.id(), senderName, session.fileName(), session.size(), hash);

            // 6. Wait for the receiver's answer (up to about two minutes).
            Decision decision = client.requestTransfer(peer, request);
            switch (decision) {
                case REFUSED -> {
                    registry.update(session, TransferState.REFUSED, "The receiver refused");
                    return;
                }
                case TIMEOUT -> {
                    registry.update(session, TransferState.FAILED, "No response from the receiver");
                    return;
                }
                case ACCEPTED -> { /* carry on */ }
            }

            // 7. Stream the file directly from disk.
            if (!registry.update(session, TransferState.ACTIVE)) {
                return; // cancelled while waiting
            }
            TransferState result = client.sendData(peer, session.id(), session.size(), () -> open(file, session));

            // 8. The receiver reports how it ended up: INTACT, CORRUPTED, CANCELLED or FAILED.
            TransferState finalState = result.isTerminal() ? result : TransferState.FAILED;
            registry.update(session, finalState, detailFor(finalState));

        } catch (CancellationException e) {
            registry.update(session, TransferState.CANCELLED, "Cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            registry.update(session, TransferState.CANCELLED, "Interrupted");
        } catch (UncheckedIOException e) {
            registry.update(session, TransferState.FAILED, describe(e.getCause()));
        } catch (IOException e) {
            if (progress.isCancelled()) {
                registry.update(session, TransferState.CANCELLED, "Cancelled");
            } else {
                registry.update(session, TransferState.FAILED, describe(e));
            }
        } catch (RuntimeException e) {
            registry.update(session, TransferState.FAILED, "Unexpected error: " + e.getMessage());
        } finally {
            receivers.remove(session.id());
        }
    }

    private InputStream open(Path file, TransferSession session) {
        try {
            return new TrackedStream(Files.newInputStream(file), session);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String detailFor(TransferState state) {
        return switch (state) {
            case INTACT -> "Arrived intact";
            case CORRUPTED -> "The receiver's checksum did not match";
            case CANCELLED -> "Cancelled by the receiver";
            default -> "The receiver reported a failure";
        };
    }

    private static String describe(Throwable e) {
        String message = (e == null) ? null : e.getMessage();
        return (message == null || message.isBlank())
                ? "Connection lost (the receiver may have cancelled)"
                : message;
    }

    /**
     * Wraps the file stream so the upload reports progress and can be cancelled: throwing an
     * IOException from read() aborts the HTTP request. When the file has been read to the end,
     * the sender moves to VERIFYING while it waits for the receiver's verdict.
     * Progress counts bytes handed to the network layer, a close estimate of bytes sent.
     */
    private final class TrackedStream extends FilterInputStream {
        private final TransferSession session;
        private boolean endReached;

        TrackedStream(InputStream in, TransferSession session) {
            super(in);
            this.session = session;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return (n == -1) ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (session.progress().isCancelled()) {
                throw new IOException("Cancelled");
            }
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                session.progress().add(n);
                registry.progress(session);
            } else if (n == -1 && !endReached) {
                endReached = true;
                registry.update(session, TransferState.VERIFYING);
            }
            return n;
        }
    }
}
