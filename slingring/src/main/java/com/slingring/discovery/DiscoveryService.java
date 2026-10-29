package com.slingring.discovery;

import com.slingring.model.Peer;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DiscoveryService {

    public static final String SERVICE_TYPE = "_slingring._tcp.local.";

    private static final int MAX_NAME_CHARS = 50;
    private static final long SHUTDOWN_WAIT_MS = 2000;

    private final String deviceId;
    private final String deviceName;
    private final int port;
    private final PeerRegistry registry;

    /*
     * One JmDNS instance per usable network interface.
     */
    private final Map<String, JmDNS> dnsByInterface = new ConcurrentHashMap<>();

    /*
     * mDNS instance name -> peer id.
     */
    private final Map<String, String> peerIdByInstance = new ConcurrentHashMap<>();

    private volatile boolean running;

    public DiscoveryService(
            String deviceId,
            String deviceName,
            int port,
            PeerRegistry registry) {
        this.deviceId = deviceId;
        this.deviceName = cleanName(
                deviceName,
                "SlingRing device");
        this.port = port;
        this.registry = registry;
    }

    // ================================================================
    // Start
    // ================================================================

    public synchronized void start() throws IOException {

        if (running) {
            return;
        }

        running = true;

        int started = 0;

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

        if (interfaces == null) {
            running = false;
            throw new IOException(
                    "No network interfaces found");
        }

        for (NetworkInterface nic : Collections.list(interfaces)) {

            Inet4Address address = findUsableIPv4(nic);

            if (address == null) {
                continue;
            }

            try {
                startOnInterface(
                        nic,
                        address);

                started++;

            } catch (Exception e) {

                System.err.println(
                        "mDNS failed on "
                                + nic.getDisplayName()
                                + " ["
                                + address.getHostAddress()
                                + "]: "
                                + e);
            }
        }

        if (started == 0) {
            running = false;

            throw new IOException(
                    "Could not start mDNS on any network interface");
        }

        System.out.println(
                "SlingRing discovery started on "
                        + started
                        + " interface(s)");
    }

    private void startOnInterface(
            NetworkInterface nic,
            Inet4Address address) throws IOException {

        String interfaceName = nic.getName();

        System.out.println(
                "Starting mDNS on "
                        + interfaceName
                        + " ("
                        + nic.getDisplayName()
                        + ") "
                        + address.getHostAddress());

        JmDNS dns = JmDNS.create(
                address,
                deviceId);

        try {

            ServiceInfo service = ServiceInfo.create(
                    SERVICE_TYPE,
                    deviceId,
                    port,
                    0,
                    0,
                    Map.of(
                            "id", deviceId,
                            "name", deviceName));

            dns.registerService(service);

            dns.addServiceListener(
                    SERVICE_TYPE,
                    listener);

            dnsByInterface.put(
                    interfaceName,
                    dns);

        } catch (IOException | RuntimeException e) {

            closeQuietly(dns);

            throw e;
        }
    }

    // ================================================================
    // Stop
    // ================================================================

    public synchronized void stop() {

        if (!running) {
            return;
        }

        running = false;

        peerIdByInstance.clear();

        for (JmDNS dns : dnsByInterface.values()) {

            try {
                dns.unregisterAllServices();
            } catch (RuntimeException ignored) {
            }

            closeQuietly(dns);
        }

        dnsByInterface.clear();
    }

    // ================================================================
    // mDNS listener
    // ================================================================

    private final ServiceListener listener = new ServiceListener() {

        @Override
        public void serviceAdded(
                ServiceEvent event) {

            event.getDNS()
                    .requestServiceInfo(
                            event.getType(),
                            event.getName());
        }

        @Override
        public void serviceResolved(
                ServiceEvent event) {

            handleResolved(
                    event.getName(),
                    event.getInfo());
        }

        @Override
        public void serviceRemoved(
                ServiceEvent event) {

            String id = peerIdByInstance.remove(
                    event.getName());

            if (id != null) {
                registry.remove(id);
            }
        }
    };

    // ================================================================
    // Peer handling
    // ================================================================

    private void handleResolved(
            String instanceName,
            ServiceInfo info) {

        if (info == null) {
            return;
        }

        String id = info.getPropertyString("id");

        if (id == null
                || id.isBlank()
                || id.length() > 64) {

            return;
        }

        /*
         * Don't discover ourselves.
         */
        if (id.equals(deviceId)) {
            return;
        }

        Inet4Address[] addresses = info.getInet4Addresses();

        if (addresses.length == 0) {
            return;
        }

        String ip = addresses[0].getHostAddress();

        String name = cleanName(
                info.getPropertyString("name"),
                ip);

        try {

            Peer peer = new Peer(
                    id,
                    name,
                    ip,
                    info.getPort());

            peerIdByInstance.put(
                    instanceName,
                    id);

            registry.add(peer);

            System.out.println(
                    "Discovered peer: "
                            + name
                            + " @ "
                            + ip
                            + ":"
                            + info.getPort());

        } catch (IllegalArgumentException ignored) {
            // Invalid peer announcement.
        }
    }

    // ================================================================
    // Network interface handling
    // ================================================================

    private static Inet4Address findUsableIPv4(
            NetworkInterface nic) {

        try {

            if (!nic.isUp()
                    || nic.isLoopback()
                    || !nic.supportsMulticast()) {

                return null;
            }

        } catch (SocketException e) {
            return null;
        }

        /*
         * Prefer private IPv4 addresses.
         */
        Inet4Address fallback = null;

        for (InetAddress address : Collections.list(
                nic.getInetAddresses())) {

            if (!(address instanceof Inet4Address ipv4)) {
                continue;
            }

            if (ipv4.isLoopbackAddress()
                    || ipv4.isLinkLocalAddress()) {

                continue;
            }

            if (ipv4.isSiteLocalAddress()) {
                return ipv4;
            }

            if (fallback == null) {
                fallback = ipv4;
            }
        }

        return fallback;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static String cleanName(
            String raw,
            String fallback) {

        if (raw == null) {
            return fallback;
        }

        String cleaned = raw.replaceAll("\\p{Cntrl}", "")
                .trim();

        if (cleaned.length() > MAX_NAME_CHARS) {
            cleaned = cleaned
                    .substring(0, MAX_NAME_CHARS)
                    .trim();
        }

        return cleaned.isEmpty()
                ? fallback
                : cleaned;
    }

    private static void closeQuietly(
            JmDNS dns) {

        try {
            dns.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }
}