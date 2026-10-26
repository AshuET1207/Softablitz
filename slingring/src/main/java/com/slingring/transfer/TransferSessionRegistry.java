package com.slingring.transfer;

import com.slingring.event.EventBus;
import com.slingring.model.TransferSession;
import com.slingring.model.TransferState;
import com.slingring.history.HistoryStore;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every transfer session on this device and is the single place where a
 * session's state changes. Services never call session.transitionTo() directly;
 * they go through update(), which also:
 *
 *   - publishes TRANSFER_UPDATE (a TransferSession.View) so the UI refreshes, and
 *   - appends the outcome to the history file when a terminal state is reached.
 *
 * Progress events are throttled to about five per second per transfer, because the
 * copy loops report after every 64 KB chunk.
 */
public class TransferSessionRegistry {

    private static final long PROGRESS_INTERVAL_MS = 200;

    private final Map<String, TransferSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProgressEmit = new ConcurrentHashMap<>();
    private final EventBus eventBus;
    private final HistoryStore history;

    public TransferSessionRegistry(EventBus eventBus, HistoryStore history) {
        this.eventBus = eventBus;
        this.history = history;
    }

    /** Start tracking a session and tell the UI about it. */
    public void register(TransferSession session) {
        sessions.put(session.id(), session);
        publish(session);
    }

    /**
     * Move a session to a new state. Returns false if it had already finished (for example
     * it was cancelled while a worker was still running), in which case nothing is published
     * or recorded. Workers can use the result to notice they should stop.
     */
    public boolean update(TransferSession session, TransferState state, String detail) {
        if (!session.transitionTo(state, detail)) {
            return false;
        }
        if (state.isTerminal()) {
            lastProgressEmit.remove(session.id());
            record(session);
        }
        publish(session);
        return true;
    }

    public boolean update(TransferSession session, TransferState state) {
        return update(session, state, "");
    }

    /** Cancel: flip the flag the worker's loops check, then mark the session CANCELLED right away. */
    public boolean cancel(TransferSession session, String detail) {
        session.requestCancel();
        return update(session, TransferState.CANCELLED, detail);
    }

    /** Called by copy and hash loops after each chunk; publishes at most every 200 ms. */
    public void progress(TransferSession session) {
        if (session.state().isTerminal()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastProgressEmit.get(session.id());
        if (last == null || now - last >= PROGRESS_INTERVAL_MS) {
            lastProgressEmit.put(session.id(), now);
            publish(session);
        }
    }

    public Optional<TransferSession> find(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    /** Snapshots of all sessions this run, newest first (for initial UI load). */
    public List<TransferSession.View> views() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(TransferSession::startedAt).reversed())
                .map(TransferSession::toView)
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private void publish(TransferSession session) {
        eventBus.publish(EventBus.Types.TRANSFER_UPDATE, session.toView());
    }

    private void record(TransferSession session) {
        try {
            history.append(session);
        } catch (IOException | RuntimeException e) {
            // A logging failure must never fail a transfer.
            System.err.println("Could not write transfer history: " + e);
        }
    }
}
