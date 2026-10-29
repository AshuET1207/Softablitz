/**
 * Send Files View
 * Provides file selection via drag-and-drop, native desktop browse,
 * path input with live validation, recipient device selection, and transfer dispatch.
 */

import { Icons } from "../icons.js";
import { escapeHtml, formatBytes } from "../utils.js";
import { Api } from "../api.js";

export function renderSend(state, container, handlers, initialParams = {}) {
  const { peers = [], systemPaths = {} } = state;
  let selectedPeerId = initialParams.peerId || (peers.length > 0 ? peers[0].id : "");
  let selectedFile = null; // { path, name, size }
  let isSending = false;

  function update() {
    container.innerHTML = `
      <div class="view-send">
        <div class="view-header">
          <div>
            <h2 class="view-title">Send Files</h2>
            <p class="view-subtitle">Select a local file and choose the destination device on your network.</p>
          </div>
          <button class="btn btn-secondary btn-sm" id="send-connect-manual">
            ${Icons.plus(16)}
            <span>Connect by IP</span>
          </button>
        </div>

        <div class="send-flow-grid">
          <!-- Left Column: File Selection -->
          <div class="send-col">
            <div class="card">
              <div class="card-header">
                <div class="card-title-group">
                  <div class="card-icon-pill">${Icons.file(18)}</div>
                  <div>
                    <h3 class="card-title">1. Select File</h3>
                    <p class="card-subtitle">Choose any file to stream directly from disk</p>
                  </div>
                </div>
              </div>

              <div class="card-body">
                ${selectedFile ? `
                  <div class="selected-file-card">
                    <div class="file-icon-wrap">${Icons.file(32)}</div>
                    <div class="file-info-wrap">
                      <div class="file-title" title="${escapeHtml(selectedFile.name)}">${escapeHtml(selectedFile.name)}</div>
                      <div class="file-size">${formatBytes(selectedFile.size)}</div>
                      <div class="file-path font-mono" title="${escapeHtml(selectedFile.path)}">${escapeHtml(selectedFile.path)}</div>
                    </div>
                    <button class="btn btn-ghost btn-icon" id="btn-remove-file" title="Remove selected file">
                      ${Icons.x(18)}
                    </button>
                  </div>
                ` : `
                  <!-- Drop Zone -->
                  <div class="file-drop-area" id="send-drop-area" tabindex="0" role="button" aria-label="Drop files or click to browse">
                    <input type="file" id="file-input-hidden" style="display: none;" />
                    <div class="drop-circle">${Icons.upload(32)}</div>
                    <h4 class="drop-heading">Drag and drop your file here</h4>
                    <p class="drop-subheading">or use desktop file browser</p>
                    <div class="drop-buttons">
                      <button type="button" class="btn btn-secondary btn-sm" id="btn-browse-native">
                        ${Icons.folder(16)}
                        <span>Browse Files…</span>
                      </button>
                    </div>
                  </div>

                  <!-- Manual Path Input Accordion / Fallback -->
                  <div class="path-input-group">
                    <label for="manual-file-path" class="form-label">
                      <span>Or enter absolute path on this computer:</span>
                    </label>
                    <div class="input-with-button">
                      <input 
                        type="text" 
                        id="manual-file-path" 
                        class="form-input font-mono" 
                        placeholder="e.g. C:\\Users\\Name\\Documents\\archive.zip"
                        autocomplete="off"
                        spellcheck="false"
                      />
                      <button class="btn btn-secondary" id="btn-validate-path" type="button">
                        Verify
                      </button>
                    </div>
                    <div id="path-validation-error" class="form-error" style="display: none;"></div>
                  </div>
                `}
              </div>
            </div>
          </div>

          <!-- Right Column: Recipient Selection & Submit -->
          <div class="send-col">
            <div class="card">
              <div class="card-header">
                <div class="card-title-group">
                  <div class="card-icon-pill">${Icons.devices(18)}</div>
                  <div>
                    <h3 class="card-title">2. Choose Recipient</h3>
                    <p class="card-subtitle">Select which device should receive this file</p>
                  </div>
                </div>
              </div>

              <div class="card-body">
                ${peers.length === 0 ? `
                  <div class="empty-state py-4">
                    <div class="empty-icon">${Icons.wifi(28)}</div>
                    <h4 class="empty-title">No Devices Discovered</h4>
                    <p class="empty-desc">Open Sling Ring on another device on this network, or connect manually.</p>
                    <button class="btn btn-secondary btn-sm" id="send-empty-manual-btn">
                      ${Icons.plus(16)}
                      <span>Enter Device IP</span>
                    </button>
                  </div>
                ` : `
                  <div class="peer-selector-list">
                    ${peers.map(peer => `
                      <label class="peer-radio-label ${peer.id === selectedPeerId ? 'selected' : ''}">
                        <input 
                          type="radio" 
                          name="target-peer" 
                          value="${escapeHtml(peer.id)}" 
                          ${peer.id === selectedPeerId ? 'checked' : ''}
                        />
                        <div class="peer-avatar">${Icons.laptop(20)}</div>
                        <div class="peer-details">
                          <span class="peer-name">${escapeHtml(peer.name)}</span>
                          <span class="peer-ip font-mono">${escapeHtml(peer.ip)}:${peer.port}</span>
                        </div>
                        <div class="peer-check-indicator">${Icons.check(16)}</div>
                      </label>
                    `).join('')}
                  </div>
                `}
              </div>

              <div class="card-footer">
                <button 
                  class="btn btn-primary btn-block btn-lg" 
                  id="btn-start-transfer"
                  ${!selectedFile || !selectedPeerId || peers.length === 0 || isSending ? 'disabled' : ''}
                >
                  ${isSending ? `
                    <span class="spinner"></span>
                    <span>Starting Transfer…</span>
                  ` : `
                    ${Icons.send(18)}
                    <span>Send File Now</span>
                  `}
                </button>
                <p class="footer-hint">
                  The receiver must approve the transfer request before bytes stream.
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    `;

    bindEvents();
  }

  function bindEvents() {
    container.querySelector("#send-connect-manual")?.addEventListener("click", () => handlers.onOpenManualPeerModal());
    container.querySelector("#send-empty-manual-btn")?.addEventListener("click", () => handlers.onOpenManualPeerModal());

    // Peer radio change
    container.querySelectorAll("input[name='target-peer']").forEach(radio => {
      radio.addEventListener("change", (e) => {
        selectedPeerId = e.target.value;
        container.querySelectorAll(".peer-radio-label").forEach(l => l.classList.remove("selected"));
        e.target.closest(".peer-radio-label")?.classList.add("selected");
        const btn = container.querySelector("#btn-start-transfer");
        if (btn) btn.disabled = !selectedFile || !selectedPeerId || isSending;
      });
    });

    // Remove file
    container.querySelector("#btn-remove-file")?.addEventListener("click", () => {
      selectedFile = null;
      update();
    });

    // Native browse button
    container.querySelector("#btn-browse-native")?.addEventListener("click", async () => {
      try {
        const res = await Api.browseFileNative();
        if (res && res.selected && res.path) {
          selectedFile = {
            path: res.path,
            name: res.name || res.path.split(/[\\/]/).pop(),
            size: res.size || 0
          };
          update();
          handlers.notify("File selected: " + selectedFile.name, "info");
        } else if (res && res.supported === false) {
          // Native dialog not supported in headless mode, trigger hidden HTML file input
          container.querySelector("#file-input-hidden")?.click();
        }
      } catch (err) {
        handlers.notify(err.message, "error");
      }
    });

    // Hidden input for fallback
    const hiddenInput = container.querySelector("#file-input-hidden");
    hiddenInput?.addEventListener("change", async (e) => {
      const file = e.target.files?.[0];
      if (file) {
        handleFileObject(file);
      }
    });

    // Drop area
    const dropArea = container.querySelector("#send-drop-area");
    if (dropArea) {
      dropArea.addEventListener("dragover", (e) => {
        e.preventDefault();
        dropArea.classList.add("dragover");
      });
      dropArea.addEventListener("dragleave", () => dropArea.classList.remove("dragover"));
      dropArea.addEventListener("drop", (e) => {
        e.preventDefault();
        dropArea.classList.remove("dragover");
        const file = e.dataTransfer.files?.[0];
        if (file) {
          handleFileObject(file);
        }
      });
    }

    // Manual path verification
    const pathInput = container.querySelector("#manual-file-path");
    const validateBtn = container.querySelector("#btn-validate-path");
    const errorEl = container.querySelector("#path-validation-error");

    const doValidate = async () => {
      const path = pathInput?.value?.trim();
      if (!path) return;
      validateBtn.disabled = true;
      if (errorEl) errorEl.style.display = "none";

      try {
        const res = await Api.validateFile(path);
        if (res && res.valid) {
          selectedFile = {
            path: res.path,
            name: res.name,
            size: res.size
          };
          update();
          handlers.notify(`File verified: ${res.name} (${formatBytes(res.size)})`, "success");
        } else {
          if (errorEl) {
            errorEl.textContent = (res && res.error) || "Invalid file path";
            errorEl.style.display = "block";
          }
        }
      } catch (err) {
        if (errorEl) {
          errorEl.textContent = err.message;
          errorEl.style.display = "block";
        }
      } finally {
        validateBtn.disabled = false;
      }
    };

    validateBtn?.addEventListener("click", doValidate);
    pathInput?.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        doValidate();
      }
    });

    // Send submit button
    const sendBtn = container.querySelector("#btn-start-transfer");
    sendBtn?.addEventListener("click", async () => {
      if (!selectedFile || !selectedPeerId || isSending) return;
      isSending = true;
      sendBtn.disabled = true;

      try {
        const res = await Api.sendFile(selectedFile.path, selectedPeerId);
        handlers.notify("Transfer initiated. Awaiting receiver approval…", "success");
        handlers.onNavigate("transfers");
      } catch (err) {
        handlers.notify(`Send failed: ${err.message}`, "error");
        isSending = false;
        sendBtn.disabled = false;
      }
    });
  }

  async function handleFileObject(file) {
    // In Electron, File objects have a .path property
    if (file.path) {
      try {
        const res = await Api.validateFile(file.path);
        if (res && res.valid) {
          selectedFile = {
            path: res.path,
            name: res.name,
            size: res.size
          };
          update();
          return;
        }
      } catch (_) {}
      selectedFile = {
        path: file.path,
        name: file.name,
        size: file.size
      };
      update();
      return;
    }

    // In a browser where file.path is not exposed, prompt user to confirm or enter path
    const promptPath = prompt(
      `Sling Ring streams files directly from disk.\nPlease confirm or enter the absolute path for "${file.name}":`,
      `C:\\Users\\...\\${file.name}`
    );
    if (promptPath && promptPath.trim()) {
      try {
        const res = await Api.validateFile(promptPath.trim());
        if (res && res.valid) {
          selectedFile = {
            path: res.path,
            name: res.name,
            size: res.size
          };
          update();
        } else {
          handlers.notify((res && res.error) || "Could not access file at path", "error");
        }
      } catch (err) {
        handlers.notify(err.message, "error");
      }
    }
  }

  // If initial file was passed (e.g. dropped on dashboard)
  if (initialParams.droppedFile) {
    handleFileObject(initialParams.droppedFile);
  } else {
    update();
  }
}

