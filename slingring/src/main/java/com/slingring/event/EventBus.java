package com.slingring.event;

import javafx.application.Platform;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Small in-process publish/subscribe hub (observer pattern).
 * Services publish events from worker threads; the UI subscribes and updates itself.
 *
 * Design choices:
 *  - Events are identified by a string type and carry an Object payload (kept simple on purpose).
 *  - publish() runs listeners on the calling thread, so listeners must be quick.
 *    UI listeners should use {@link #subscribeOnUi}, which hops to the JavaFX thread.
 *  - One failing listener never stops the others or the publisher.
 *  - Thread-safe: subscribe, unsubscribe and publish can run concurrently.
 */
public class EventBus {

    /** Event type names used across the app. Keep them here to avoid typos. */
    public static final class Types {
        private Types() { }

        public static final String PEER_ADDED = "peer-added";               // payload: Peer
        public static final String PEER_REMOVED = "peer-removed";           // payload: String peerId
        public static final String TRANSFER_REQUEST = "transfer-request";   // payload: TransferSession.View (receiver, needs consent)
        public static final String TRANSFER_UPDATE = "transfer-update";     // payload: TransferSession.View (progress and state changes)
        public static final String DISCOVERY_ERROR = "discovery-error";     // payload: String message
    }

    /** Handle returned by subscribe; close it to stop receiving events. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final Map<String, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    /** Receive events of one type on whichever thread publishes them. */
    public Subscription subscribe(String type, Consumer<Object> listener) {
        List<Consumer<Object>> list =
                listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    /** Typed convenience: the payload is cast for you. Wrong-typed payloads are skipped. */
    public <T> Subscription subscribe(String type, Class<T> payloadType, Consumer<T> listener) {
        return subscribe(type, payload -> {
            if (payloadType.isInstance(payload)) {
                listener.accept(payloadType.cast(payload));
            }
        });
    }

    /**
     * Receive events on the JavaFX Application Thread. Use this for anything that
     * touches controls (labels, lists, progress bars).
     */
    public <T> Subscription subscribeOnUi(String type, Class<T> payloadType, Consumer<T> listener) {
        return subscribe(type, payloadType, payload -> runOnUi(() -> listener.accept(payload)));
    }

    /** Deliver an event to every current subscriber of this type. */
    public void publish(String type, Object payload) {
        List<Consumer<Object>> list = listeners.get(type);
        if (list == null) {
            return;
        }
        for (Consumer<Object> listener : list) {   // CopyOnWriteArrayList: safe to iterate while others change it
            try {
                listener.accept(payload);
            } catch (RuntimeException e) {
                System.err.println("EventBus listener for '" + type + "' failed: " + e);
            }
        }
    }

    /** Drop all listeners (used on shutdown and in tests). */
    public void clear() {
        listeners.clear();
    }

    private static void runOnUi(Runnable task) {
        try {
            if (Platform.isFxApplicationThread()) {
                task.run();
            } else {
                Platform.runLater(task);
            }
        } catch (IllegalStateException toolkitNotRunning) {
            // JavaFX is not started (e.g. unit tests): run directly so logic stays testable.
            task.run();
        }
    }
}