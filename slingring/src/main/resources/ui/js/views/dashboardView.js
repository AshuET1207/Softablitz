/**
 * Dashboard View
 * The primary overview screen showing device identity, nearby peers,
 * active transfer summary, incoming alerts, and network status.
 */

import { Icons } from "../icons.js";
import { escapeHtml, formatBytes, formatDate } from "../utils.js";

export function renderDashboard(state, container, handlers) {
  const { deviceName, deviceId, discoveryError, peers = [], sessions = [], history = [] } = state;
  const incomingPending = sessions.filter(s => s.direction === "RECEIVING" && s.state === "PENDING");
  const activeTransfers = sessions.filter(s => !["INTACT", "CORRUPTED", "REFUSED", "CANCELLED", "FAILED"].includes(s.state));

  container.innerHTML = `
    <div class="view-dashboard">
      <!-- Welcome Hero Banner -->
      <section class="dashboard-hero">
        <div class="hero-content">
          <div class="hero-badge">
            <span class="status-dot ${discoveryError ? 'status-dot-warning' : 'status-dot-active'}"></span>
            <span>${discoveryError ? 'Discovery Issue' : 'Online · Local Network'}</span>
          </div>
          <h1 class="hero-title">Ready to Transfer</h1>
          <p class="hero-subtitle">
            Transfer files peer-to-peer over your local network. No cloud, zero limits, verified with SHA-256.
          </p>
          <div class="hero-actions">
            <button class="btn btn-primary" id="dash-btn-send">
              ${Icons.send(18)}
              <span>Send Files</span>
            </button>
            <button class="btn btn-secondary" id="dash-btn-manual">
              ${Icons.plus(18)}
              <span>Connect by IP</span>
            </button>
          </div>
        </div>

        <div class="hero-device-card">
          <div class="device-card-header">
            <div class="device-avatar">${Icons.laptop(24)}</div>
            <div class="device-meta">
              <span class="device-label">THIS DEVICE</span>
              <h3 class="device-name">${escapeHtml(deviceName)}</h3>
            </div>
          </div>
          <div class="device-card-body">
            <div class="meta-row">
              <span class="meta-label">Device ID</span>
              <span class="meta-value font-mono">${escapeHtml(deviceId ? deviceId.slice(0, 8) : 'Unknown')}</span>
            </div>
            <div class="meta-row">
              <span class="meta-label">Discovery Mode</span>
              <span class="meta-value">mDNS &amp; Direct</span>
            </div>
          </div>
        </div>
      </section>

      ${incomingPending.length > 0 ? `
        <!-- Incoming Request Alert Banner -->
        <section class="alert-banner alert-banner-incoming">
          <div class="alert-icon">${Icons.incoming(22)}</div>
          <div class="alert-body">
            <h4>Incoming File Transfer Request</h4>
            <p><strong>${escapeHtml(incomingPending[0].peerName)}</strong> wants to send you <strong>${escapeHtml(incomingPending[0].fileName)}</strong> (${formatBytes(incomingPending[0].size)}).</p>
          </div>
          <div class="alert-actions">
            <button class="btn btn-sm btn-primary" id="dash-btn-review-incoming">
              Review (${incomingPending.length})
            </button>
          </div>
        </section>
      ` : ''}

      <!-- Grid: Nearby Devices & Quick Transfer -->
      <div class="dashboard-grid">
        <!-- Nearby Devices Column -->
        <div class="dash-col">
          <div class="card">
            <div class="card-header">
              <div class="card-title-group">
                <div class="card-icon-pill">${Icons.wifi(18)}</div>
                <div>
                  <h3 class="card-title">Nearby Devices</h3>
                  <p class="card-subtitle">${peers.length} device${peers.length === 1 ? '' : 's'} discovered on Wi-Fi/LAN</p>
                </div>
              </div>
              <button class="btn btn-ghost btn-sm" id="dash-refresh-peers" title="Refresh state">
                ${Icons.refresh(16)}
              </button>
            </div>

            <div class="card-body">
              ${peers.length === 0 ? `
                <div class="empty-state">
                  <div class="radar-animation" aria-hidden="true">
                    <div class="radar-circle circle-1"></div>
                    <div class="radar-circle circle-2"></div>
                    <div class="radar-circle circle-3"></div>
                    <div class="radar-center">${Icons.radar(20)}</div>
                  </div>
                  <h4 class="empty-title">Scanning for Devices</h4>
                  <p class="empty-desc">Make sure other computers have Sling Ring open on the same local network.</p>
                  <button class="btn btn-secondary btn-sm" id="dash-empty-manual">
                    ${Icons.plus(16)}
                    <span>Connect by IP</span>
                  </button>
                </div>
              ` : `
                <div class="device-list">
                  ${peers.map(peer => `
                    <div class="device-item" data-peer-id="${escapeHtml(peer.id)}">
                      <div class="device-item-avatar">${Icons.laptop(20)}</div>
                      <div class="device-item-info">
                        <div class="device-item-name">${escapeHtml(peer.name)}</div>
                        <div class="device-item-address font-mono">${escapeHtml(peer.ip)}:${peer.port}</div>
                      </div>
                      <button class="btn btn-primary btn-sm btn-send-to-peer" data-peer-id="${escapeHtml(peer.id)}">
                        ${Icons.send(14)}
                        <span>Send</span>
                      </button>
                    </div>
                  `).join('')}
                </div>
              `}
            </div>
          </div>
        </div>

        <!-- Right Column: Active Transfers / Quick Drop -->
        <div class="dash-col">
          <!-- Active Transfers Glance -->
          <div class="card">
            <div class="card-header">
              <div class="card-title-group">
                <div class="card-icon-pill">${Icons.transfers(18)}</div>
                <div>
                  <h3 class="card-title">Transfer Queue</h3>
                  <p class="card-subtitle">${activeTransfers.length} active transfer${activeTransfers.length === 1 ? '' : 's'}</p>
                </div>
              </div>
              <button class="btn btn-ghost btn-sm" id="dash-view-all-transfers">
                View All
              </button>
            </div>

            <div class="card-body">
              ${activeTransfers.length === 0 ? `
                <div class="drop-zone-mini" id="dash-drop-zone">
                  <div class="drop-icon">${Icons.upload(28)}</div>
                  <div class="drop-text">
                    <strong>Drop a file here to send</strong>
                    <span>or click to browse from this computer</span>
                  </div>
                </div>
              ` : `
                <div class="transfers-summary-list">
                  ${activeTransfers.slice(0, 3).map(session => {
                    const pct = session.size > 0 ? Math.min(100, Math.round((session.bytesTransferred / session.size) * 100)) : 0;
                    return `
                      <div class="transfer-mini-item">
                        <div class="transfer-mini-header">
                          <span class="file-name" title="${escapeHtml(session.fileName)}">${escapeHtml(session.fileName)}</span>
                          <span class="state-pill state-${escapeHtml(session.state.toLowerCase())}">${escapeHtml(session.state)}</span>
                        </div>
                        <div class="progress-bar-wrap">
                          <div class="progress-bar-fill" style="width: ${pct}%"></div>
                        </div>
                        <div class="transfer-mini-footer">
                          <span>${formatBytes(session.bytesTransferred)} / ${formatBytes(session.size)}</span>
                          <span>${session.direction === 'SENDING' ? 'To' : 'From'} ${escapeHtml(session.peerName)}</span>
                        </div>
                      </div>
                    `;
                  }).join('')}
                </div>
              `}
            </div>
          </div>

          <!-- Recent Activity -->
          <div class="card">
            <div class="card-header">
              <div class="card-title-group">
                <div class="card-icon-pill">${Icons.history(18)}</div>
                <div>
                  <h3 class="card-title">Recent Activity</h3>
                  <p class="card-subtitle">Latest verified transfers</p>
                </div>
              </div>
              <button class="btn btn-ghost btn-sm" id="dash-view-history">
                History
              </button>
            </div>

            <div class="card-body">
              ${history.length === 0 ? `
                <p class="text-secondary text-center text-sm py-3">No completed transfers yet</p>
              ` : `
                <div class="recent-history-list">
                  ${history.slice(0, 4).map(entry => `
                    <div class="recent-history-item">
                      <div class="history-item-icon ${entry.direction === 'SENDING' ? 'icon-outgoing' : 'icon-incoming'}">
                        ${entry.direction === 'SENDING' ? Icons.arrowUpRight(16) : Icons.arrowDownLeft(16)}
                      </div>
                      <div class="history-item-info">
                        <div class="history-item-file" title="${escapeHtml(entry.fileName)}">${escapeHtml(entry.fileName)}</div>
                        <div class="history-item-sub">
                          <span>${entry.direction === 'SENDING' ? 'Sent to' : 'Received from'} ${escapeHtml(entry.peerName)}</span>
                          <span>·</span>
                          <span>${formatBytes(entry.size)}</span>
                        </div>
                      </div>
                      <div class="history-item-status">
                        <span class="state-pill state-${escapeHtml(entry.state.toLowerCase())}">${escapeHtml(entry.state)}</span>
                      </div>
                    </div>
                  `).join('')}
                </div>
              `}
            </div>
          </div>
        </div>
      </div>
    </div>
  `;

  // Attach event handlers
  container.querySelector("#dash-btn-send")?.addEventListener("click", () => handlers.onNavigate("send"));
  container.querySelector("#dash-btn-manual")?.addEventListener("click", () => handlers.onOpenManualPeerModal());
  container.querySelector("#dash-empty-manual")?.addEventListener("click", () => handlers.onOpenManualPeerModal());
  container.querySelector("#dash-refresh-peers")?.addEventListener("click", () => handlers.onRefresh());
  container.querySelector("#dash-btn-review-incoming")?.addEventListener("click", () => handlers.onNavigate("incoming"));
  container.querySelector("#dash-view-all-transfers")?.addEventListener("click", () => handlers.onNavigate("transfers"));
  container.querySelector("#dash-view-history")?.addEventListener("click", () => handlers.onNavigate("history"));

  // Send to peer buttons
  container.querySelectorAll(".btn-send-to-peer").forEach(btn => {
    btn.addEventListener("click", (e) => {
      const peerId = e.currentTarget.getAttribute("data-peer-id");
      handlers.onNavigate("send", { peerId });
    });
  });

  // Quick drop zone
  const dropZone = container.querySelector("#dash-drop-zone");
  if (dropZone) {
    dropZone.addEventListener("click", () => handlers.onNavigate("send"));
    dropZone.addEventListener("dragover", (e) => {
      e.preventDefault();
      dropZone.classList.add("dragover");
    });
    dropZone.addEventListener("dragleave", () => dropZone.classList.remove("dragover"));
    dropZone.addEventListener("drop", (e) => {
      e.preventDefault();
      dropZone.classList.remove("dragover");
      if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        handlers.onNavigate("send", { droppedFile: e.dataTransfer.files[0] });
      }
    });
  }
}

