/**
 * Incoming Requests View
 * Displays files waiting for receiver authorization.
 * Allows choosing save destination (Downloads, Desktop, Custom) and accepting or refusing.
 */

import { Icons } from "../icons.js";
import { escapeHtml, formatBytes } from "../utils.js";

export function renderIncoming(state, container, handlers) {
  const { sessions = [] } = state;
  const pendingRequests = sessions.filter(s => s.direction === "RECEIVING" && s.state === "PENDING");

  container.innerHTML = `
    <div class="view-incoming">
      <div class="view-header">
        <div>
          <h2 class="view-title">Incoming Requests</h2>
          <p class="view-subtitle">Review incoming files from other devices. Approvals are valid for 120 seconds.</p>
        </div>
        <div class="header-badge">
          <span class="badge ${pendingRequests.length > 0 ? 'badge-primary' : 'badge-neutral'}">
            ${pendingRequests.length} Pending
          </span>
        </div>
      </div>

      ${pendingRequests.length === 0 ? `
        <div class="card empty-container">
          <div class="empty-state">
            <div class="empty-icon">${Icons.incoming(36)}</div>
            <h4 class="empty-title">No Incoming Requests</h4>
            <p class="empty-desc">When another device on your network sends you a file, it will appear here for your approval.</p>
          </div>
        </div>
      ` : `
        <div class="requests-grid">
          ${pendingRequests.map(req => `
            <div class="request-card" data-transfer-id="${escapeHtml(req.id)}">
              <div class="request-card-header">
                <div class="request-sender">
                  <div class="sender-avatar">${Icons.laptop(20)}</div>
                  <div>
                    <span class="sender-label">From:</span>
                    <strong class="sender-name">${escapeHtml(req.peerName)}</strong>
                  </div>
                </div>
                <span class="badge badge-warning">Awaiting Approval</span>
              </div>

              <div class="request-file-box">
                <div class="file-icon-wrap">${Icons.file(28)}</div>
                <div class="file-info-wrap">
                  <div class="file-title" title="${escapeHtml(req.fileName)}">${escapeHtml(req.fileName)}</div>
                  <div class="file-meta">
                    <span class="file-size">${formatBytes(req.size)}</span>
                    <span>·</span>
                    <span>SHA-256 Verified on delivery</span>
                  </div>
                </div>
              </div>

              <div class="request-card-actions">
                <button class="btn btn-primary btn-accept" data-id="${escapeHtml(req.id)}" data-name="${escapeHtml(req.fileName)}">
                  ${Icons.check(16)}
                  <span>Accept &amp; Save…</span>
                </button>
                <button class="btn btn-danger btn-refuse" data-id="${escapeHtml(req.id)}">
                  ${Icons.x(16)}
                  <span>Refuse</span>
                </button>
              </div>
            </div>
          `).join('')}
        </div>
      `}
    </div>
  `;

  // Attach handlers
  container.querySelectorAll(".btn-accept").forEach(btn => {
    btn.addEventListener("click", () => {
      const id = btn.getAttribute("data-id");
      const name = btn.getAttribute("data-name");
      handlers.onOpenAcceptModal(id, name);
    });
  });

  container.querySelectorAll(".btn-refuse").forEach(btn => {
    btn.addEventListener("click", async () => {
      const id = btn.getAttribute("data-id");
      btn.disabled = true;
      try {
        await handlers.onRefuseTransfer(id);
      } catch (err) {
        handlers.notify(err.message, "error");
        btn.disabled = false;
      }
    });
  });
}

