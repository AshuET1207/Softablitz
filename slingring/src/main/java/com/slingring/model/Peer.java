package com.slingring.model;

import java.util.Objects;

public record Peer(String id, String name, String ip, int port) {
 
    public Peer {
        Objects.requireNonNull(id, "id");  /* checks the reference is not null */ 
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ip, "ip");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
    }
 
    /** return base URL for talking to this peer */
    public String baseUrl() {
        String host = ip.contains(":") ? "[" + ip + "]" : ip;
        return "http://" + host + ":" + port;
    }
 
    /** Peers are the same device if the id matches, even if the address changed. */
    public boolean sameDevice(Peer other) {
        return other != null && id.equals(other.id);
    }
 
    @Override
    public String toString() {
        return name + " (" + ip + ":" + port + ")";
    }
}
 