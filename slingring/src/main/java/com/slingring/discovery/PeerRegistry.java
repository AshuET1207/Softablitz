package com.slingring.discovery;

import com.slingring.event.EventBus;
import com.slingring.model.Peer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live list of nearby devices. Two sources feed it:
 *  - DiscoveryService (mDNS), which adds and removes peers as they come and go
 *  - the manual IP fallback, which adds a peer after PeerClient.fetchInfo succeeds
 *
 * Peers are keyed by device id, so a device that changes address simply replaces
 * its old entry. Every change is published on the EventBus so the UI can update
 * itself; the UI subscribes with EventBus.subscribeOnUi.
 *
 *   PEER_ADDED   payload: Peer    (also sent when an existing peer's details changed)
 *   PEER_REMOVED payload: String  (the peer id)
 *
 * Thread-safe: mDNS callbacks arrive on JmDNS threads while the UI reads.
 */
public class PeerRegistry {

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final EventBus eventBus;

    public PeerRegistry(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /** Add a peer, or update it if the id is already known. Repeat announcements are ignored quietly. */
    public void add(Peer peer) {
        Peer previous = peers.put(peer.id(), peer);
        if (!peer.equals(previous)) {
            eventBus.publish(EventBus.Types.PEER_ADDED, peer);
        }
    }

    /** Remove a peer by id. Does nothing (and publishes nothing) if it is not in the list. */
    public void remove(String id) {
        if (peers.remove(id) != null) {
            eventBus.publish(EventBus.Types.PEER_REMOVED, id);
        }
    }

    public Optional<Peer> find(String id) {
        return Optional.ofNullable(peers.get(id));
    }

    /** Snapshot of all peers, sorted by name, safe to hold on to. */
    public List<Peer> all() {
        return peers.values().stream()
                .sorted(Comparator.comparing(Peer::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}