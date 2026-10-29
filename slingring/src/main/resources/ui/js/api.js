/**
 * Authenticated API client for Sling Ring.
 * Handles loopback API requests with Bearer authentication.
 * Token is retrieved safely from memory and never exposed in logs or DOM.
 */

let authToken = "";
let apiBaseUrl = "";

export async function initApi() {
  // 1. Injected token from UiApiServer
  if (typeof window.__SLINGRING_TOKEN__ === "string" && window.__SLINGRING_TOKEN__.trim()) {
    authToken = window.__SLINGRING_TOKEN__.trim();
  }

  // 2. Electron bridge fallback if available
  if (!authToken && window.slingring && typeof window.slingring.getBackendConfig === "function") {
    try {
      const config = await window.slingring.getBackendConfig();
      if (config) {
        authToken = config.token || config.uiToken || "";
        if (config.apiBaseUrl) {
          apiBaseUrl = config.apiBaseUrl;
        } else if (config.uiPort) {
          apiBaseUrl = `http://127.0.0.1:${config.uiPort}`;
        }
      }
    } catch (_) {
      // Ignore bridge errors
    }
  }

  // 3. Current origin as base URL
  if (!apiBaseUrl) {
    apiBaseUrl = window.location.origin;
  }
}

export function getAuthToken() {
  return authToken;
}

export function getBaseUrl() {
  return apiBaseUrl;
}

export async function request(path, options = {}) {
  if (!authToken) {
    await initApi();
  }

  if (!authToken) {
    throw new Error("UI authentication token is missing. Please refresh the page.");
  }

  const url = new URL(path, `${apiBaseUrl}/`);
  const headers = {
    Authorization: `Bearer ${authToken}`,
    ...(options.body ? { "Content-Type": "application/json" } : {}),
    ...options.headers
  };

  const response = await fetch(url, { ...options, headers });

  if (response.status === 204) {
    return null;
  }

  let data = null;
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    try {
      data = await response.json();
    } catch (_) {
      // Non-parseable json
    }
  }

  if (!response.ok) {
    const errorMsg = (data && data.error) ? data.error : `Request failed (${response.status})`;
    throw new Error(errorMsg);
  }

  return data;
}

export const Api = {
  async getState() {
    return request("/api/state");
  },

  async getHistory(limit = 100) {
    const clamped = Math.min(Math.max(1, limit), 500);
    return request(`/api/history?limit=${clamped}`);
  },

  async sendFile(filePath, peerId) {
    return request("/api/send", {
      method: "POST",
      body: JSON.stringify({ path: filePath, peerId })
    });
  },

  async connectManualPeer(ip, port) {
    return request("/api/peers/manual", {
      method: "POST",
      body: JSON.stringify({ ip, port: Number(port) })
    });
  },

  async acceptTransfer(transferId, saveDir) {
    return request(`/api/transfers/${encodeURIComponent(transferId)}/accept`, {
      method: "POST",
      body: JSON.stringify({ saveDir })
    });
  },

  async refuseTransfer(transferId) {
    return request(`/api/transfers/${encodeURIComponent(transferId)}/refuse`, {
      method: "POST"
    });
  },

  async cancelTransfer(transferId) {
    return request(`/api/transfers/${encodeURIComponent(transferId)}/cancel`, {
      method: "POST"
    });
  },

  async getSystemPaths() {
    try {
      return await request("/api/system/paths");
    } catch (_) {
      return { home: "", downloads: "", desktop: "", documents: "" };
    }
  },

  async validateFile(path) {
    return request("/api/validate/file", {
      method: "POST",
      body: JSON.stringify({ path })
    });
  },

  async validateDir(path) {
    return request("/api/validate/dir", {
      method: "POST",
      body: JSON.stringify({ path })
    });
  },

  async browseFileNative() {
    try {
      return await request("/api/browse/file", { method: "POST" });
    } catch (_) {
      return { selected: false, supported: false };
    }
  },

  async browseDirNative() {
    try {
      return await request("/api/browse/dir", { method: "POST" });
    } catch (_) {
      return { selected: false, supported: false };
    }
  }
};

