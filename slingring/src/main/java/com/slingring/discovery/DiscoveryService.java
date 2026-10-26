package com.slingring.discovery;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import com.slingring.model.Peer;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Announces this device on the local network and watches for others, using
 * mDNS / DNS-SD through JmDNS (pure Java, no native install).
 *
 * Each device registers a service of type "_slingring._tcp" whose TXT record
 * carries its id and display name; the service port is the PeerServer port.
 * Other devices' announcements are turned into Peer objects and handed to the
 * PeerRegistry. Peers that say goodbye or whose records expire are removed.
 *
 * Everything that arrives over mDNS comes from strangers on the network, so it
 * is validated before use. A malformed announcement is skipped, never fatal.
 *
 * If discovery is blocked (client isolation, firewall, no multicast), start()
 * may fail or simply find nobody. The user can then use the manual IP entry.
 */
public class DiscoveryService {

    public static final String SERVICE_TYPE = "_slingring._tcp.local.";

    // TXT record values are limited to 255 bytes; stay well under that even with multi-byte characters.
    private static final int MAX_NAME_CHARS = 50;
    private static final long SHUTDOWN_WAIT_MS = 2000;

    private final String deviceId;
    private final String deviceName;
    private final int port;
    private final PeerRegistry registry;

    /** mDNS instance name -> peer id, so removals can find the right peer even if JmDNS renamed an instance. */
    private final Map<String, String> peerIdByInstance = new ConcurrentHashMap<>();

    private JmDNS jmdns;

    public DiscoveryService(String deviceId, String deviceName, int port, PeerRegistry registry) {
        this.deviceId = deviceId;
        this.deviceName = cleanName(deviceName, "SlingRing device");
        this.port = port;
        this.registry = registry;
    }

    /** Registers this device and starts listening. Blocks briefly, so call it off the UI thread. */
    public synchronized void start() throws IOException {
        if (jmdns != null) {
            return; // already running
        }

        InetAddress lan = pickLanAddress();
        JmDNS dns = (lan != null) ? JmDNS.create(lan, deviceId) : JmDNS.create();
        try {
            // The instance name is our unique id, which avoids clashes between two devices
            // that share a display name (like two laptops both called "DESKTOP-1").
            ServiceInfo mine = ServiceInfo.create(SERVICE_TYPE, deviceId, port, 0, 0,
                    Map.of("id", deviceId, "name", deviceName));
            dns.registerService(mine);
            dns.addServiceListener(SERVICE_TYPE, listener);
        } catch (IOException | RuntimeException e) {
            closeQuietly(dns); // don't leak sockets and threads if registration fails
            throw e;
        }
        jmdns = dns;
    }

    /**
     * Says goodbye to the network and releases sockets. JmDNS can take a second or
     * two to close, so this waits at most two seconds and never hangs the window closing.
     */
    public void stop() {
        JmDNS toClose;
        synchronized (this) {
            toClose = jmdns;
            jmdns = null;
        }
        peerIdByInstance.clear();
        if (toClose == null) {
            return;
        }

        Thread closer = new Thread(() -> {
            try {
                toClose.unregisterAllServices(); // sends the goodbye so others drop us immediately
            } catch (RuntimeException ignored) {
                // shutting down anyway
            }
            closeQuietly(toClose);
        }, "slingring-mdns-shutdown");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(SHUTDOWN_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ listening

    private final ServiceListener listener = new ServiceListener() {
        @Override
        public void serviceAdded(ServiceEvent event) {
            // Only the name is known at this point; ask for the address, port and TXT record.
            event.getDNS().requestServiceInfo(event.getType(), event.getName());
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            handleResolved(event.getName(), event.getInfo());
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            String id = peerIdByInstance.remove(event.getName());
            if (id != null) {
                registry.remove(id);
            }
        }
    };

    private void handleResolved(String instanceName, ServiceInfo info) {
        if (info == null) {
            return;
        }

        String id = info.getPropertyString("id");
        if (id == null || id.isBlank() || id.length() > 64) {
            return;                       // not a well-formed SlingRing announcement
        }
        if (id.equals(deviceId)) {
            return;                       // that's us; don't list ourselves
        }

        // IPv4 only, to keep addresses simple (IPv6 link-local addresses need scope ids).
        Inet4Address[] addresses = info.getInet4Addresses();
        if (addresses.length == 0) {
            return;
        }
        String ip = addresses[0].getHostAddress();
        String name = cleanName(info.getPropertyString("name"), ip);

        try {
            Peer peer = new Peer(id, name, ip, info.getPort()); // validates the port
            peerIdByInstance.put(instanceName, id);
            registry.add(peer);
        } catch (IllegalArgumentException invalid) {
            // Hostile or buggy announcement (for example, an impossible port). Ignore it.
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Pick the address JmDNS should use. JmDNS.create() alone often binds to the wrong
     * interface (on Linux the hostname can resolve to 127.0.1.1), so look for a real
     * LAN address: up, multicast-capable, non-loopback IPv4, preferring private ranges.
     * Returns null to let JmDNS decide.
     *
     * Known limit: with several active networks (VPN, Docker, Wi-Fi plus Ethernet) the first
     * suitable one wins. Running one JmDNS per interface would be the full fix.
     */
    private static InetAddress pickLanAddress() throws SocketException {
        InetAddress fallback = null;
        Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
        if (nics == null) {
            return null;
        }
        for (NetworkInterface nic : Collections.list(nics)) {
            try {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || !nic.supportsMulticast()) {
                    continue;
                }
            } catch (SocketException skip) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address
                        && !address.isLoopbackAddress()
                        && !address.isLinkLocalAddress()) {
                    if (address.isSiteLocalAddress()) {
                        return address;        // 192.168.x.x, 10.x.x.x, 172.16-31.x.x
                    }
                    if (fallback == null) {
                        fallback = address;
                    }
                }
            }
        }
        return fallback;
    }

    /** Display names come from strangers: strip control characters, trim, limit length. */
    private static String cleanName(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.length() > MAX_NAME_CHARS) {
            cleaned = cleaned.substring(0, MAX_NAME_CHARS).trim();
        }
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static void closeQuietly(JmDNS dns) {
        try {
            dns.close();
        } catch (IOException | RuntimeException ignored) {
            // nothing useful to do
        }
    }
}