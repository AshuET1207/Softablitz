/**
 * Active Transfers View
 * Live monitoring of in-flight and recently finished transfers.
 * Displays real-time progress bars, speeds, ETAs, checksum verifications, and cancellation.
 */

import { Icons } from "../icons.js";
import { escapeHtml, formatBytes, formatSpeed, formatEta } from "../utils.js";

export function renderTransfers(state, container, handlers) {
  const { sessions = [] } = state;
  let filter = "all"; // 'all' | 'sending' | 'receiving' | 'active'

  function update() {
    let filtered = sessions;
    if (filter === "sending") {
      filtered = sessions.filter(s => s.direction === "SENDING");
    } else if (filter === "receiving") {
      filtered = sessions.filter(s => s.direction === "RECEIVING");
    } else if (filter === "active") {
      filtered = sessions.filter(s => !["INTACT", "CORRUPTED", "REFUSED", "CANCELLED", "FAILED"].includes(s.state));
    }

    container.innerHTML = `
      <div class="view-transfers">
        <div class="view-header">
          <div>
            <h2 class="view-title">Active Transfers</h2>
            <p class="view-subtitle">Monitor live file transfers, streaming speeds, and checksum verification status.</p>
          </div>
          <div class="filter-tabs">
            <button class="filter-tab ${filter === 'all' ? 'active' : ''}" data-filter="all">
              All (${sessions.length})
            </button>
            <button class="filter-tab ${filter === 'active' ? 'active' : ''}" data-filter="active">
              Active (${sessions.filter(s => !['INTACT', 'CORRUPTED', 'REFUSED', 'CANCELLED', 'FAILED'].includes(s.state)).length})
            </button>
            <button class="filter-tab ${filter === 'sending' ? 'active' : ''}" data-filter="sending">
              Outgoing
            </button>
            <button class="filter-tab ${filter === 'receiving' ? 'active' : ''}" data-filter="receiving">
              Incoming
            </button>
          </div>
        </div>

        ${filtered.length === 0 ? `
          <div class="card empty-container">
            <div class="empty-state">
              <div class="empty-icon">${Icons.transfers(36)}</div>
              <h4 class="empty-title">No Transfers Matching Filter</h4>
              <p class="empty-desc">Send a file or wait for an incoming transfer to see live progress.</p>
              <button class="btn btn-primary btn-sm" id="btn-transfers-go-send">
                ${Icons.send(16)}
                <span>Send a File</span>
              </button>
            </div>
          </div>
        ` : `
          <div class="transfers-list">
            ${filtered.map(session => {
              const isTerminal = ["INTACT", "CORRUPTED", "REFUSED", "CANCELLED", "FAILED"].includes(session.state);
              const pct = session.size > 0 
                ? Math.min(100, Math.max(0, Math.round((session.bytesTransferred / session.size) * 100))) 
                : (isTerminal ? 100 : 0);
              const isSending = session.direction === "SENDING";
              const speedText = formatSpeed(session.bytesPerSecond);
              const etaText = formatEta(session.etaSeconds);

              return `
                <div class="transfer-card ${isTerminal ? 'terminal' : 'in-flight'}" data-id="${escapeHtml(session.id)}">
                  <div class="transfer-card-top">
                    <div class="transfer-direction-badge ${isSending ? 'outgoing' : 'incoming'}">
                      ${isSending ? Icons.arrowUpRight(16) : Icons.arrowDownLeft(16)}
                      <span>${isSending ? 'Sending' : 'Receiving'}</span>
                    </div>

                    <div class="transfer-file-details">
                      <h4 class="file-title" title="${escapeHtml(session.fileName)}">${escapeHtml(session.fileName)}</h4>
                      <div class="file-peer-sub">
                        <span>${isSending ? 'To:' : 'From:'} <strong>${escapeHtml(session.peerName)}</strong></span>
                        <span>·</span>
                        <span>${formatBytes(session.size)}</span>
                      </div>
                    </div>

                    <div class="transfer-status-badge">
                      <span class="state-pill state-${escapeHtml(session.state.toLowerCase())}">
                        ${escapeHtml(session.state)}
                      </span>
                    </div>
                  </div>

                  <!-- Progress Section -->
                  <div class="transfer-progress-section">
                    <div class="progress-bar-wrap">
                      <div class="progress-bar-fill state-${escapeHtml(session.state.toLowerCase())}" style="width: ${pct}%"></div>
                    </div>

                    <div class="progress-meta-row">
                      <div class="progress-bytes">
                        <strong>${formatBytes(session.bytesTransferred)}</strong> of ${formatBytes(session.size)} (${pct}%)
                      </div>

                      <div class="progress-speed-eta">
                        ${speedText ? `<span class="badge-speed">${speedText}</span>` : ''}
                        ${etaText ? `<span class="badge-eta">${etaText}</span>` : ''}
                        ${session.detail ? `<span class="detail-note" title="${escapeHtml(session.detail)}">${escapeHtml(session.detail)}</span>` : ''}
                      </div>
                    </div>
                  </div>

                  <!-- Actions footer if in-flight -->
                  ${!isTerminal ? `
                    <div class="transfer-card-footer">
                      <span class="subtle-notice">
                        ${session.state === 'HASHING' ? 'Computing SHA-256 before request…' : ''}
                        ${session.state === 'PENDING' ? (isSending ? 'Waiting for receiver to accept…' : 'Awaiting your approval…') : ''}
                        ${session.state === 'ACTIVE' ? 'Streaming direct peer-to-peer data…' : ''}
                        ${session.state === 'VERIFYING' ? 'Verifying SHA-256 integrity…' : ''}
                      </span>
                      <button class="btn btn-danger btn-sm btn-cancel-transfer" data-id="${escapeHtml(session.id)}">
                        ${Icons.ban(14)}
                        <span>Cancel</span>
                      </button>
                    </div>
                  ` : ''}
                </div>
              `;
            }).join('')}
          </div>
        `}
      </div>
    `;

    bindEvents();
  }

  function bindEvents() {
    container.querySelectorAll(".filter-tab").forEach(tab => {
      tab.addEventListener("click", () => {
        filter = tab.getAttribute("data-filter");
        update();
      });
    });

    container.querySelector("#btn-transfers-go-send")?.addEventListener("click", () => handlers.onNavigate("send"));

    container.querySelectorAll(".btn-cancel-transfer").forEach(btn => {
      btn.addEventListener("click", async () => {
        const id = btn.getAttribute("data-id");
        if (confirm("Are you sure you want to cancel this transfer?")) {
          btn.disabled = true;
          try {
            await handlers.onCancelTransfer(id);
          } catch (err) {
            handlers.notify(err.message, "error");
            btn.disabled = false;
          }
        }
      });
    });
  }

  update();
}

