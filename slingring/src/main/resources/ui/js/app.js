/**
 * Main Application Orchestrator for Sling Ring
 * Manages view switching, global modals, notifications, SSE stream lifecycle,
 * and state synchronization across components.
 */

import { Api, initApi } from "./api.js";
import { eventStream } from "./events.js";
import { Icons } from "./icons.js";
import { Sound } from "./sound.js";
import { escapeHtml } from "./utils.js";

import { renderDashboard } from "./views/dashboardView.js";
import { renderSend } from "./views/sendView.js";
import { renderIncoming } from "./views/incomingView.js";
import { renderTransfers } from "./views/transfersView.js";
import { renderHistory } from "./views/historyView.js";
import { renderSettings } from "./views/settingsView.js";

class SlingRingApp {
  constructor() {
    this.state = {
      deviceId: "",
      deviceName: "Sling Ring",
      discoveryError: null,
      peers: [],
      sessions: [],
      history: [],
      systemPaths: {},
      currentView: "dashboard",
      viewParams: {},
      isOnline: false
    };

    this.container = document.getElementById("view-container");
    this.toastContainer = document.getElementById("toast-container");
    this.acceptModal = document.getElementById("modal-accept-transfer");
    this.manualPeerModal = document.getElementById("modal-manual-peer");
  }

  async init() {
    // Apply user preferences from storage
    if (localStorage.getItem("slingring_compact_mode") === "true") {
      document.body.classList.add("compact-density");
    }
    if (localStorage.getItem("slingring_reduced_motion") === "true") {
      document.body.classList.add("reduced-motion");
    }

    // Initialize API client
    await initApi();

    // Bind sidebar navigation and header actions
    this.bindGlobalNavigation();
    this.bindModals();
    this.bindKeyboardShortcuts();

    // Fetch initial state
    await this.refreshAll();

    // Connect to SSE stream
    this.initEventStream();

    // Periodical fallback sync (every 10 seconds just in case)
    setInterval(() => this.backgroundSync(), 10000);
  }

  async refreshAll() {
    try {
      const [stateData, historyData, systemPaths] = await Promise.all([
        Api.getState().catch(err => {
          this.notify("Failed to fetch state: " + err.message, "error");
          return null;
        }),
        Api.getHistory(100).catch(() => []),
        Api.getSystemPaths().catch(() => ({}))
      ]);

      if (stateData) {
        this.state.deviceId = stateData.deviceId || "";
        this.state.deviceName = stateData.deviceName || "Sling Ring";
        this.state.discoveryError = stateData.discoveryError || null;
        this.state.peers = stateData.peers || [];
        this.state.sessions = stateData.sessions || [];
      }
      this.state.history = historyData || [];
      this.state.systemPaths = systemPaths || {};

      this.updateHeaderAndSidebar();
      this.renderCurrentView();
    } catch (err) {
      this.notify("Initialization error: " + err.message, "error");
    }
  }

  async backgroundSync() {
    try {
      const stateData = await Api.getState();
      if (stateData) {
        this.state.peers = stateData.peers || [];
        this.state.sessions = stateData.sessions || [];
        this.state.discoveryError = stateData.discoveryError || null;
        this.updateHeaderAndSidebar();
        if (this.state.currentView === "transfers" || this.state.currentView === "dashboard") {
          this.renderCurrentView();
        }
      }
    } catch (_) {}
  }

  initEventStream() {
    eventStream.on("connection-status", ({ status }) => {
      this.state.isOnline = (status === "connected");
      this.updateHeaderAndSidebar();
      if (status === "connected") {
        this.backgroundSync();
      }
    });

    eventStream.on("peer-added", (peer) => {
      const existingIdx = this.state.peers.findIndex(p => p.id === peer.id);
      if (existingIdx >= 0) {
        this.state.peers[existingIdx] = peer;
      } else {
        this.state.peers.push(peer);
        Sound.playNotice();
        this.notify(`Device discovered: ${peer.name} (${peer.ip})`, "info");
      }
      this.updateHeaderAndSidebar();
      if (this.state.currentView === "dashboard" || this.state.currentView === "send") {
        this.renderCurrentView();
      }
    });

    eventStream.on("peer-removed", (peerId) => {
      this.state.peers = this.state.peers.filter(p => p.id !== peerId);
      this.updateHeaderAndSidebar();
      if (this.state.currentView === "dashboard" || this.state.currentView === "send") {
        this.renderCurrentView();
      }
    });

    eventStream.on("transfer-request", (view) => {
      // Receiver incoming request
      const existing = this.state.sessions.find(s => s.id === view.id);
      if (!existing) {
        this.state.sessions.unshift(view);
      } else {
        Object.assign(existing, view);
      }
      Sound.playAlert();
      this.notify(
        `Incoming file request from ${view.peerName}: ${view.fileName}`, 
        "warning", 
        { actionLabel: "Review", onAction: () => this.navigate("incoming") }
      );
      this.updateHeaderAndSidebar();
      this.renderCurrentView();
    });

    eventStream.on("transfer-update", (view) => {
      const existing = this.state.sessions.find(s => s.id === view.id);
      const wasTerminal = existing && ["INTACT", "CORRUPTED", "REFUSED", "CANCELLED", "FAILED"].includes(existing.state);

      if (!existing) {
        this.state.sessions.unshift(view);
      } else {
        Object.assign(existing, view);
      }

      if (!wasTerminal) {
        if (view.state === "INTACT") {
          Sound.playSuccess();
          this.notify(`Transfer verified: ${view.fileName} arrived intact!`, "success");
          this.refreshHistory();
        } else if (view.state === "CORRUPTED") {
          Sound.playAlert();
          this.notify(`Checksum mismatch for ${view.fileName}!`, "error");
          this.refreshHistory();
        } else if (view.state === "REFUSED") {
          this.notify(`Transfer refused: ${view.fileName}`, "error");
          this.refreshHistory();
        } else if (view.state === "FAILED") {
          this.notify(`Transfer failed: ${view.fileName} (${view.detail || "Network error"})`, "error");
          this.refreshHistory();
        }
      }

      this.updateHeaderAndSidebar();
      if (["dashboard", "transfers", "incoming"].includes(this.state.currentView)) {
        this.renderCurrentView();
      }
    });

    eventStream.on("discovery-error", (errMsg) => {
      this.state.discoveryError = errMsg;
      this.updateHeaderAndSidebar();
      if (this.state.currentView === "dashboard") {
        this.renderCurrentView();
      }
    });

    eventStream.connect();
  }

  async refreshHistory() {
    try {
      this.state.history = await Api.getHistory(100);
      if (this.state.currentView === "history" || this.state.currentView === "dashboard") {
        this.renderCurrentView();
      }
    } catch (_) {}
  }

  updateHeaderAndSidebar() {
    // Current device indicator in sidebar footer
    const footerName = document.getElementById("footer-device-name");
    if (footerName) footerName.textContent = this.state.deviceName || "Sling Ring";

    const footerId = document.getElementById("footer-device-id");
    if (footerId && this.state.deviceId) footerId.textContent = this.state.deviceId.slice(0, 8);

    // Online status indicator
    const footerDot = document.getElementById("footer-status-dot");
    if (footerDot) {
      footerDot.className = `status-dot ${this.state.isOnline ? (this.state.discoveryError ? 'status-dot-warning' : 'status-dot-active') : 'status-dot-offline'}`;
    }

    const headerStatusBadge = document.getElementById("header-status-badge");
    if (headerStatusBadge) {
      if (this.state.discoveryError) {
        headerStatusBadge.innerHTML = `<span class="badge badge-warning">${Icons.alertCircle(12)} Discovery Warning</span>`;
      } else if (this.state.isOnline) {
        headerStatusBadge.innerHTML = `<span class="badge badge-success">${Icons.wifi(12)} Local Network Active</span>`;
      } else {
        headerStatusBadge.innerHTML = `<span class="badge badge-danger">${Icons.ban(12)} Reconnecting…</span>`;
      }
    }

    // Badges for navigation
    const pendingIncoming = this.state.sessions.filter(s => s.direction === "RECEIVING" && s.state === "PENDING").length;
    const badgeIncoming = document.getElementById("badge-nav-incoming");
    if (badgeIncoming) {
      badgeIncoming.textContent = pendingIncoming > 0 ? pendingIncoming : "";
      badgeIncoming.style.display = pendingIncoming > 0 ? "inline-flex" : "none";
    }

    const activeTransfers = this.state.sessions.filter(s => !["INTACT", "CORRUPTED", "REFUSED", "CANCELLED", "FAILED"].includes(s.state)).length;
    const badgeTransfers = document.getElementById("badge-nav-transfers");
    if (badgeTransfers) {
      badgeTransfers.textContent = activeTransfers > 0 ? activeTransfers : "";
      badgeTransfers.style.display = activeTransfers > 0 ? "inline-flex" : "none";
    }
  }

  navigate(viewName, params = {}) {
    this.state.currentView = viewName;
    this.state.viewParams = params;

    // Update active class on nav links
    document.querySelectorAll(".nav-link").forEach(link => {
      const target = link.getAttribute("data-view");
      link.classList.toggle("active", target === viewName);
      if (target === viewName) {
        link.setAttribute("aria-current", "page");
      } else {
        link.removeAttribute("aria-current");
      }
    });

    this.renderCurrentView();
  }

  renderCurrentView() {
    const handlers = {
      onNavigate: (view, params) => this.navigate(view, params),
      onRefresh: () => this.refreshAll(),
      onRefreshHistory: () => this.refreshHistory(),
      onOpenManualPeerModal: () => this.openManualPeerModal(),
      onOpenAcceptModal: (id, name) => this.openAcceptModal(id, name),
      onRefuseTransfer: async (id) => {
        await Api.refuseTransfer(id);
        this.notify("Transfer refused", "info");
        await this.backgroundSync();
      },
      onCancelTransfer: async (id) => {
        await Api.cancelTransfer(id);
        this.notify("Transfer cancelled", "info");
        await this.backgroundSync();
      },
      notify: (msg, type, opts) => this.notify(msg, type, opts)
    };

    switch (this.state.currentView) {
      case "dashboard":
        renderDashboard(this.state, this.container, handlers);
        break;
      case "send":
        renderSend(this.state, this.container, handlers, this.state.viewParams);
        break;
      case "incoming":
        renderIncoming(this.state, this.container, handlers);
        break;
      case "transfers":
        renderTransfers(this.state, this.container, handlers);
        break;
      case "history":
        renderHistory(this.state, this.container, handlers);
        break;
      case "settings":
        renderSettings(this.state, this.container, handlers);
        break;
      default:
        this.navigate("dashboard");
    }
  }

  bindGlobalNavigation() {
    document.querySelectorAll(".nav-link").forEach(link => {
      link.addEventListener("click", (e) => {
        e.preventDefault();
        const view = link.getAttribute("data-view");
        this.navigate(view);
      });
    });

    document.getElementById("btn-header-manual-peer")?.addEventListener("click", () => {
      this.openManualPeerModal();
    });
  }

  bindModals() {
    // Close on backdrop click
    [this.acceptModal, this.manualPeerModal].forEach(modal => {
      modal?.addEventListener("click", (e) => {
        if (e.target === modal || e.target.classList.contains("modal-close-trigger")) {
          this.closeModal(modal);
        }
      });
    });

    // Manual peer modal submission
    const manualForm = document.getElementById("form-manual-peer");
    manualForm?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const ipInput = document.getElementById("manual-peer-ip");
      const portInput = document.getElementById("manual-peer-port");
      const submitBtn = manualForm.querySelector("button[type='submit']");
      const errBox = document.getElementById("manual-peer-error");

      const ip = ipInput?.value?.trim();
      const port = Number(portInput?.value || 8765);

      if (!ip) return;
      if (errBox) errBox.style.display = "none";
      if (submitBtn) submitBtn.disabled = true;

      try {
        const peer = await Api.connectManualPeer(ip, port);
        this.notify(`Connected to ${peer.name} (${peer.ip})!`, "success");
        this.closeModal(this.manualPeerModal);
        manualForm.reset();
        await this.backgroundSync();
      } catch (err) {
        if (errBox) {
          errBox.textContent = err.message;
          errBox.style.display = "block";
        }
      } finally {
        if (submitBtn) submitBtn.disabled = false;
      }
    });

    // Accept transfer form submission
    const acceptForm = document.getElementById("form-accept-transfer");
    acceptForm?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const transferId = acceptForm.getAttribute("data-transfer-id");
      const saveDirInput = document.getElementById("accept-save-dir");
      const submitBtn = acceptForm.querySelector("button[type='submit']");
      const errBox = document.getElementById("accept-transfer-error");

      const saveDir = saveDirInput?.value?.trim();
      if (!transferId || !saveDir) return;

      if (errBox) errBox.style.display = "none";
      if (submitBtn) submitBtn.disabled = true;

      try {
        await Api.acceptTransfer(transferId, saveDir);
        this.notify("Transfer accepted! Receiving file…", "success");
        this.closeModal(this.acceptModal);
        this.navigate("transfers");
        await this.backgroundSync();
      } catch (err) {
        if (errBox) {
          errBox.textContent = err.message;
          errBox.style.display = "block";
        }
      } finally {
        if (submitBtn) submitBtn.disabled = false;
      }
    });

    // Accept transfer presets and browse
    document.querySelectorAll(".accept-chip-btn").forEach(chip => {
      chip.addEventListener("click", () => {
        const path = chip.getAttribute("data-path");
        const input = document.getElementById("accept-save-dir");
        if (input && path) input.value = path;
      });
    });

    document.getElementById("btn-accept-browse-dir")?.addEventListener("click", async () => {
      try {
        const res = await Api.browseDirNative();
        if (res && res.selected && res.path) {
          const input = document.getElementById("accept-save-dir");
          if (input) input.value = res.path;
        }
      } catch (_) {}
    });
  }

  openManualPeerModal() {
    const errBox = document.getElementById("manual-peer-error");
    if (errBox) errBox.style.display = "none";
    this.openModal(this.manualPeerModal);
    setTimeout(() => document.getElementById("manual-peer-ip")?.focus(), 50);
  }

  openAcceptModal(transferId, fileName) {
    const modal = this.acceptModal;
    const form = document.getElementById("form-accept-transfer");
    const nameEl = document.getElementById("accept-modal-file-name");
    const dirInput = document.getElementById("accept-save-dir");
    const errBox = document.getElementById("accept-transfer-error");

    if (form) form.setAttribute("data-transfer-id", transferId);
    if (nameEl) nameEl.textContent = fileName || "Incoming File";
    if (errBox) errBox.style.display = "none";

    const defaultDir = localStorage.getItem("slingring_default_save_dir") 
      || this.state.systemPaths.downloads 
      || this.state.systemPaths.home 
      || "";
    if (dirInput) dirInput.value = defaultDir;

    // Populate preset chips
    const presetsWrap = document.getElementById("accept-chips-container");
    if (presetsWrap) {
      presetsWrap.innerHTML = `
        <span class="chips-label">Quick select:</span>
        ${this.state.systemPaths.downloads ? `
          <button type="button" class="chip-btn accept-chip-btn" data-path="${escapeHtml(this.state.systemPaths.downloads)}">Downloads</button>
        ` : ''}
        ${this.state.systemPaths.desktop ? `
          <button type="button" class="chip-btn accept-chip-btn" data-path="${escapeHtml(this.state.systemPaths.desktop)}">Desktop</button>
        ` : ''}
        ${this.state.systemPaths.documents ? `
          <button type="button" class="chip-btn accept-chip-btn" data-path="${escapeHtml(this.state.systemPaths.documents)}">Documents</button>
        ` : ''}
      `;
      presetsWrap.querySelectorAll(".accept-chip-btn").forEach(chip => {
        chip.addEventListener("click", () => {
          const path = chip.getAttribute("data-path");
          if (dirInput && path) dirInput.value = path;
        });
      });
    }

    this.openModal(modal);
    setTimeout(() => dirInput?.focus(), 50);
  }

  openModal(modal) {
    if (!modal) return;
    modal.classList.add("active");
    document.body.classList.add("modal-open");
  }

  closeModal(modal) {
    if (!modal) return;
    modal.classList.remove("active");
    document.body.classList.remove("modal-open");
  }

  bindKeyboardShortcuts() {
    window.addEventListener("keydown", (e) => {
      // Escape closes active modals
      if (e.key === "Escape") {
        if (this.acceptModal?.classList.contains("active")) {
          this.closeModal(this.acceptModal);
        } else if (this.manualPeerModal?.classList.contains("active")) {
          this.closeModal(this.manualPeerModal);
        }
      }

      // 1-6 keys switch tabs when not typing in an input
      if (!["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement?.tagName)) {
        if (e.key === "1") this.navigate("dashboard");
        if (e.key === "2") this.navigate("send");
        if (e.key === "3") this.navigate("incoming");
        if (e.key === "4") this.navigate("transfers");
        if (e.key === "5") this.navigate("history");
        if (e.key === "6") this.navigate("settings");
      }
    });
  }

  notify(message, type = "info", options = {}) {
    if (!this.toastContainer) return;

    const toast = document.createElement("div");
    toast.className = `toast toast-${type}`;
    
    let iconSvg = Icons.info(18);
    if (type === "success") iconSvg = Icons.check(18);
    if (type === "error") iconSvg = Icons.alertCircle(18);
    if (type === "warning") iconSvg = Icons.alertCircle(18);

    toast.innerHTML = `
      <div class="toast-icon">${iconSvg}</div>
      <div class="toast-message">${escapeHtml(message)}</div>
      ${options.actionLabel ? `
        <button class="toast-action-btn">${escapeHtml(options.actionLabel)}</button>
      ` : ''}
      <button class="toast-close-btn" aria-label="Close notification">${Icons.x(14)}</button>
      <div class="toast-progress"></div>
    `;

    if (options.actionLabel && typeof options.onAction === "function") {
      toast.querySelector(".toast-action-btn")?.addEventListener("click", () => {
        options.onAction();
        this.dismissToast(toast);
      });
    }

    toast.querySelector(".toast-close-btn")?.addEventListener("click", () => {
      this.dismissToast(toast);
    });

    this.toastContainer.appendChild(toast);

    // Auto dismiss after 5 seconds
    const timeout = setTimeout(() => {
      this.dismissToast(toast);
    }, 5000);

    toast._timeout = timeout;
  }

  dismissToast(toast) {
    if (toast._timeout) clearTimeout(toast._timeout);
    toast.classList.add("toast-hiding");
    toast.addEventListener("animationend", () => {
      toast.remove();
    }, { once: true });
  }
}

// Instantiate and start app on DOM ready
document.addEventListener("DOMContentLoaded", () => {
  const app = new SlingRingApp();
  app.init();
});

