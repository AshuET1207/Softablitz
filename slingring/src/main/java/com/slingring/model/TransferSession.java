package com.slingring.model;

import com.slingring.transfer.ProgressTracker;

import java.time.Instant;
import java.util.Objects;

/**
 * State of one transfer on one device. The sender and the receiver each hold
 * their own session for the same transferId (distinguished by {@link Direction}).
 *
 * Thread-safety: the state is changed from worker threads (network/IO) and read
 * from the UI thread, so all transitions go through {@link #transitionTo}.
 * Terminal states are final; once reached, later changes are ignored.
 */
public class TransferSession {

    public enum Direction { SENDING, RECEIVING }

    /**
     * Immutable snapshot for the UI and for JSON. The UI never touches the live session.
     * etaSeconds is -1 when unknown.
     */
    public record View(String id,
                       String fileName,
                       long size,
                       String peerName,
                       Direction direction,
                       TransferState state,
                       long bytesTransferred,
                       double bytesPerSecond,
                       long etaSeconds,
                       String detail) {

        /** 0.0 to 1.0, safe for ProgressBar. */
        public double fraction() {
            return size <= 0 ? 0.0 : Math.min(1.0, (double) bytesTransferred / size);
        }
    }

    private final String id;
    private final String fileName;      // display name (sanitized on the receiving side)
    private final long size;
    private volatile String sha256;     // empty on the sender until hashing finishes
    private final String peerName;      // the other device
    private final Direction direction;
    private final Instant startedAt = Instant.now();
    private final ProgressTracker progress;

    private volatile TransferState state;
    private volatile String detail = "";   // failure reason or verdict note
    private volatile Instant finishedAt;

    public TransferSession(String id, String fileName, long size, String sha256,
                           String peerName, Direction direction, TransferState initialState) {
        this.id = Objects.requireNonNull(id);
        this.fileName = Objects.requireNonNull(fileName);
        this.size = size;
        this.sha256 = Objects.requireNonNull(sha256);
        this.peerName = Objects.requireNonNull(peerName);
        this.direction = Objects.requireNonNull(direction);
        this.state = Objects.requireNonNull(initialState);
        this.progress = new ProgressTracker(size);
    }

    /** Convenience: build the receiver's session from an incoming request. */
    public static TransferSession forIncoming(TransferRequest req, String safeFileName) {
        return new TransferSession(req.transferId(), safeFileName, req.size(), req.sha256(),
                req.senderName(), Direction.RECEIVING, TransferState.PENDING);
    }

    /**
     * Move to a new state. Returns false (and changes nothing) if the session is
     * already in a terminal state, so a late "ACTIVE" can never overwrite "CANCELLED".
     */
    public synchronized boolean transitionTo(TransferState next, String detailMessage) {
        if (state.isTerminal()) {
            return false;
        }
        this.state = Objects.requireNonNull(next);
        this.detail = detailMessage == null ? "" : detailMessage;
        if (next.isTerminal()) {
            this.finishedAt = Instant.now();
        }
        return true;
    }

    public boolean transitionTo(TransferState next) {
        return transitionTo(next, "");
    }

    /**
     * Ask the worker to stop by flipping the flag its loops check. This does not change the
     * state; use TransferSessionRegistry.cancel(...) to also update the UI and the history.
     */
    public void requestCancel() {
        progress.cancel();
    }

    /** The sender fills this in once hashing finishes. */
    public void setSha256(String sha256) {
        this.sha256 = Objects.requireNonNull(sha256);
    }

    public View toView() {
        return new View(id, fileName, size, peerName, direction, state,
                progress.bytesTransferred(), progress.speed(), progress.eta(), detail);
    }

    // ---- Getters ----
    public String id() { return id; }
    public String fileName() { return fileName; }
    public long size() { return size; }
    public String sha256() { return sha256; }
    public String peerName() { return peerName; }
    public Direction direction() { return direction; }
    public TransferState state() { return state; }
    public String detail() { return detail; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public ProgressTracker progress() { return progress; }

    @Override
    public String toString() {
        return direction + " " + fileName + " [" + state + "]";
    }
}