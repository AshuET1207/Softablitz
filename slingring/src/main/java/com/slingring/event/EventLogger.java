package com.slingring.event;

import com.slingring.model.Peer;
import com.slingring.model.TransferSession;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs EventBus events to stderr.
 *
 * The logger is intentionally always enabled while the application is running.
 */
public final class EventLogger implements AutoCloseable {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PrintStream out;

    private final Map<String, String> peerNames =
            new ConcurrentHashMap<>();

    private final Map<String, String> lastStates =
            new ConcurrentHashMap<>();

    private final EventBus.Subscription peerAddedSubscription;
    private final EventBus.Subscription peerRemovedSubscription;
    private final EventBus.Subscription transferRequestSubscription;
    private final EventBus.Subscription transferUpdateSubscription;
    private final EventBus.Subscription discoveryErrorSubscription;

    private EventLogger(EventBus eventBus, PrintStream out) {
        this.out = out;

        peerAddedSubscription = eventBus.subscribe(
                EventBus.Types.PEER_ADDED,
                this::onPeerAdded
        );

        peerRemovedSubscription = eventBus.subscribe(
                EventBus.Types.PEER_REMOVED,
                this::onPeerRemoved
        );

        transferRequestSubscription = eventBus.subscribe(
                EventBus.Types.TRANSFER_REQUEST,
                this::onTransferRequest
        );

        transferUpdateSubscription = eventBus.subscribe(
                EventBus.Types.TRANSFER_UPDATE,
                this::onTransferUpdate
        );

        discoveryErrorSubscription = eventBus.subscribe(
                EventBus.Types.DISCOVERY_ERROR,
                this::onDiscoveryError
        );

        log("EventLogger attached");
    }

    /**
     * Attach an EventLogger to the EventBus.
     *
     * The returned instance must be retained by App for its lifetime.
     */
    public static EventLogger attach(EventBus eventBus) {
        return new EventLogger(eventBus, System.err);
    }

    private void onPeerAdded(Object payload) {
        if (!(payload instanceof Peer peer)) {
            log("PEER_ADDED payload=" + clean(payload));
            return;
        }

        peerNames.put(peer.id(), peer.name());

        log(
                "PEER_ADDED"
                        + " id=" + clean(peer.id())
                        + " name=\"" + clean(peer.name()) + "\""
                        + " address=" + clean(peer.ip())
                        + ":" + peer.port()
        );
    }

    private void onPeerRemoved(Object payload) {
        String id = String.valueOf(payload);

        String name = peerNames.remove(id);

        if (name == null) {
            log("PEER_REMOVED id=" + clean(id));
        } else {
            log(
                    "PEER_REMOVED"
                            + " id=" + clean(id)
                            + " name=\"" + clean(name) + "\""
            );
        }
    }

    private void onTransferRequest(Object payload) {
        log(
                "TRANSFER_REQUEST"
                        + " payload=" + clean(payload)
        );
    }

    private void onTransferUpdate(Object payload) {
        if (!(payload instanceof TransferSession.View view)) {
            log(
                    "TRANSFER_UPDATE"
                            + " payload=" + clean(payload)
            );
            return;
        }

        String id = String.valueOf(view.id());
        String state = String.valueOf(view.state());

        String previous = lastStates.put(id, state);

        // Only print when the state changes.
        if (!state.equals(previous)) {
            log(
                    "TRANSFER_UPDATE"
                            + " id=" + clean(id)
                            + " state=" + state
            );
        }
    }

    private void onDiscoveryError(Object payload) {
        log(
                "DISCOVERY_ERROR"
                        + " " + clean(payload)
        );
    }

    private void log(String message) {
        out.println(
                "["
                        + LocalTime.now().format(TIME)
                        + "] "
                        + message
        );

        out.flush();
    }

    private static String clean(Object value) {
        if (value == null) {
            return "null";
        }

        return String.valueOf(value)
                .replaceAll("\\p{Cntrl}", "?");
    }

    @Override
    public void close() {
        peerAddedSubscription.close();
        peerRemovedSubscription.close();
        transferRequestSubscription.close();
        transferUpdateSubscription.close();
        discoveryErrorSubscription.close();

        log("EventLogger detached");
    }
}