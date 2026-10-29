/**
 * Transfer History View
 * Searchable, filterable audit log of completed file transfers.
 * Displays SHA-256 integrity checksums, timestamps, outcomes, and sizes.
 */

import { Icons } from "../icons.js";
import { escapeHtml, formatBytes, formatDate, truncateHash, copyToClipboard } from "../utils.js";

export function renderHistory(state, container, handlers) {
  const { history = [] } = state;
  let searchQuery = "";
  let statusFilter = "all"; // 'all' | 'intact' | 'failed' | 'refused' | 'cancelled'
  let directionFilter = "all"; // 'all' | 'SENDING' | 'RECEIVING'

  function update() {
    let list = history.filter(item => {
      // Search
      if (searchQuery) {
        const q = searchQuery.toLowerCase();
        const matchName = (item.fileName || "").toLowerCase().includes(q);
        const matchPeer = (item.peerName || "").toLowerCase().includes(q);
        const matchHash = (item.sha256 || "").toLowerCase().includes(q);
        if (!matchName && !matchPeer && !matchHash) return false;
      }
      // Status
      if (statusFilter !== "all") {
        if (item.state.toLowerCase() !== statusFilter.toLowerCase()) return false;
      }
      // Direction
      if (directionFilter !== "all") {
        if (item.direction !== directionFilter) return false;
      }
      return true;
    });

    container.innerHTML = `
      <div class="view-history">
        <div class="view-header">
          <div>
            <h2 class="view-title">Transfer History</h2>
            <p class="view-subtitle">Review past file transfers, delivery verdicts, and SHA-256 checksums.</p>
          </div>
          <button class="btn btn-secondary btn-sm" id="btn-refresh-history">
            ${Icons.refresh(16)}
            <span>Refresh</span>
          </button>
        </div>

        <!-- Filter & Search Toolbar -->
        <div class="history-toolbar">
          <div class="search-input-wrap">
            <span class="search-icon">${Icons.search(18)}</span>
            <input 
              type="text" 
              id="history-search-input" 
              class="form-input search-input" 
              placeholder="Search by file name, peer, or hash…" 
              value="${escapeHtml(searchQuery)}"
            />
            ${searchQuery ? `
              <button class="btn-clear-search" id="btn-clear-search" title="Clear search">
                ${Icons.x(14)}
              </button>
            ` : ''}
          </div>

          <div class="toolbar-filters">
            <!-- Status Filter -->
            <select class="form-select" id="history-filter-status">
              <option value="all" ${statusFilter === 'all' ? 'selected' : ''}>All Statuses</option>
              <option value="INTACT" ${statusFilter === 'INTACT' ? 'selected' : ''}>Intact (Success)</option>
              <option value="FAILED" ${statusFilter === 'FAILED' ? 'selected' : ''}>Failed</option>
              <option value="REFUSED" ${statusFilter === 'REFUSED' ? 'selected' : ''}>Refused</option>
              <option value="CANCELLED" ${statusFilter === 'CANCELLED' ? 'selected' : ''}>Cancelled</option>
              <option value="CORRUPTED" ${statusFilter === 'CORRUPTED' ? 'selected' : ''}>Corrupted</option>
            </select>

            <!-- Direction Filter -->
            <select class="form-select" id="history-filter-direction">
              <option value="all" ${directionFilter === 'all' ? 'selected' : ''}>All Directions</option>
              <option value="SENDING" ${directionFilter === 'SENDING' ? 'selected' : ''}>Sent (Outgoing)</option>
              <option value="RECEIVING" ${directionFilter === 'RECEIVING' ? 'selected' : ''}>Received (Incoming)</option>
            </select>
          </div>
        </div>

        <!-- History Records Table / Cards -->
        ${list.length === 0 ? `
          <div class="card empty-container">
            <div class="empty-state">
              <div class="empty-icon">${Icons.history(36)}</div>
              <h4 class="empty-title">
                ${history.length === 0 ? 'No History Recorded' : 'No Transfers Match Your Filters'}
              </h4>
              <p class="empty-desc">
                ${history.length === 0 
                  ? 'Completed transfers will be recorded and cryptographically verified here.' 
                  : 'Try adjusting your search query or status filter.'}
              </p>
            </div>
          </div>
        ` : `
          <div class="card p-0">
            <div class="table-responsive">
              <table class="history-table">
                <thead>
                  <tr>
                    <th>File</th>
                    <th>Direction</th>
                    <th>Peer Device</th>
                    <th>Size</th>
                    <th>Timestamp</th>
                    <th>Status</th>
                    <th>SHA-256 Hash</th>
                  </tr>
                </thead>
                <tbody>
                  ${list.map(entry => {
                    const isOutgoing = entry.direction === "SENDING";
                    const isSuccess = entry.state === "INTACT";

                    return `
                      <tr class="history-row">
                        <td class="cell-file">
                          <div class="file-name-cell">
                            <span class="file-type-icon">${Icons.file(18)}</span>
                            <div class="file-text">
                              <strong title="${escapeHtml(entry.fileName)}">${escapeHtml(entry.fileName)}</strong>
                              ${entry.detail ? `<small class="text-secondary block">${escapeHtml(entry.detail)}</small>` : ''}
                            </div>
                          </div>
                        </td>

                        <td class="cell-direction">
                          <span class="badge ${isOutgoing ? 'badge-outgoing' : 'badge-incoming'}">
                            ${isOutgoing ? Icons.arrowUpRight(12) : Icons.arrowDownLeft(12)}
                            <span>${isOutgoing ? 'Sent' : 'Received'}</span>
                          </span>
                        </td>

                        <td class="cell-peer">
                          <span class="font-medium">${escapeHtml(entry.peerName)}</span>
                        </td>

                        <td class="cell-size font-mono">
                          ${formatBytes(entry.size)}
                        </td>

                        <td class="cell-date text-secondary text-sm">
                          ${formatDate(entry.timestamp)}
                        </td>

                        <td class="cell-status">
                          <span class="state-pill state-${escapeHtml(entry.state.toLowerCase())}">
                            ${escapeHtml(entry.state)}
                          </span>
                        </td>

                        <td class="cell-hash font-mono">
                          ${entry.sha256 ? `
                            <div class="hash-pill" title="${escapeHtml(entry.sha256)}">
                              <span>${truncateHash(entry.sha256)}</span>
                              <button class="btn-copy-hash" data-hash="${escapeHtml(entry.sha256)}" title="Copy full SHA-256 hash">
                                ${Icons.copy(14)}
                              </button>
                            </div>
                          ` : `
                            <span class="text-muted">—</span>
                          `}
                        </td>
                      </tr>
                    `;
                  }).join('')}
                </tbody>
              </table>
            </div>
          </div>
        `}
      </div>
    `;

    bindEvents();
  }

  function bindEvents() {
    container.querySelector("#btn-refresh-history")?.addEventListener("click", async () => {
      await handlers.onRefreshHistory();
    });

    const searchInput = container.querySelector("#history-search-input");
    searchInput?.addEventListener("input", (e) => {
      searchQuery = e.target.value.trim();
      update();
    });

    container.querySelector("#btn-clear-search")?.addEventListener("click", () => {
      searchQuery = "";
      update();
    });

    container.querySelector("#history-filter-status")?.addEventListener("change", (e) => {
      statusFilter = e.target.value;
      update();
    });

    container.querySelector("#history-filter-direction")?.addEventListener("change", (e) => {
      directionFilter = e.target.value;
      update();
    });

    // Copy hash buttons
    container.querySelectorAll(".btn-copy-hash").forEach(btn => {
      btn.addEventListener("click", async (e) => {
        e.stopPropagation();
        const hash = btn.getAttribute("data-hash");
        if (hash) {
          await copyToClipboard(hash);
          handlers.notify("SHA-256 hash copied to clipboard", "success");
        }
      });
    });
  }

  update();
}

