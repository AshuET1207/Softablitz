/**
 * Server-Sent Events (SSE) manager with Bearer authentication.
 * Uses fetch + ReadableStream to support custom authorization headers.
 * Includes auto-reconnection and event dispatching.
 */

import { getAuthToken, getBaseUrl } from "./api.js";

export class EventStream {
  constructor() {
    this.listeners = new Map();
    this.abortController = null;
    this.reconnectTimer = null;
    this.reconnectDelay = 1000;
    this.maxReconnectDelay = 15000;
    this.isConnected = false;
    this.isManualClose = false;
  }

  on(eventName, callback) {
    if (!this.listeners.has(eventName)) {
      this.listeners.set(eventName, new Set());
    }
    this.listeners.get(eventName).add(callback);
    return () => this.off(eventName, callback);
  }

  off(eventName, callback) {
    if (this.listeners.has(eventName)) {
      this.listeners.get(eventName).delete(callback);
    }
  }

  emit(eventName, data) {
    const set = this.listeners.get(eventName);
    if (set) {
      for (const callback of set) {
        try {
          callback(data);
        } catch (err) {
          console.error(`Error in event listener for ${eventName}:`, err);
        }
      }
    }
  }

  async connect() {
    this.isManualClose = false;
    if (this.abortController) {
      this.abortController.abort();
    }

    const token = getAuthToken();
    const baseUrl = getBaseUrl();
    if (!token) {
      this.scheduleReconnect();
      return;
    }

    this.abortController = new AbortController();
    this.emit("connection-status", { status: "connecting" });

    try {
      const url = new URL("/api/events", `${baseUrl}/`);
      const response = await fetch(url, {
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: "text/event-stream"
        },
        signal: this.abortController.signal
      });

      if (!response.ok) {
        throw new Error(`SSE stream rejected: ${response.status}`);
      }

      this.isConnected = true;
      this.reconnectDelay = 1000; // Reset delay
      this.emit("connection-status", { status: "connected" });

      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let buffer = "";

      while (!this.isManualClose) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split("\n\n");
        buffer = parts.pop() || "";

        for (const block of parts) {
          this.parseBlock(block);
        }
      }
    } catch (err) {
      if (!this.isManualClose) {
        this.isConnected = false;
        this.emit("connection-status", { status: "disconnected", error: err.message });
        this.scheduleReconnect();
      }
    }
  }

  parseBlock(block) {
    if (!block.trim()) return;

    let eventName = "message";
    let dataStr = "";

    const lines = block.split("\n");
    for (const line of lines) {
      if (line.startsWith("event:")) {
        eventName = line.substring(6).trim();
      } else if (line.startsWith("data:")) {
        const d = line.substring(5).trim();
        dataStr = dataStr ? `${dataStr}\n${d}` : d;
      }
    }

    if (dataStr) {
      let parsed = dataStr;
      try {
        parsed = JSON.parse(dataStr);
      } catch (_) {
        // Leave as string if not JSON
      }
      this.emit(eventName, parsed);
    }
  }

  scheduleReconnect() {
    if (this.isManualClose || this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.reconnectDelay = Math.min(this.reconnectDelay * 1.5, this.maxReconnectDelay);
      this.connect();
    }, this.reconnectDelay);
  }

  disconnect() {
    this.isManualClose = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.abortController) {
      this.abortController.abort();
      this.abortController = null;
    }
    this.isConnected = false;
    this.emit("connection-status", { status: "disconnected" });
  }
}

export const eventStream = new EventStream();

