/**
 * Settings View
 * Configuration for save directories, audio notifications, appearance,
 * network diagnostics, and system information.
 */

import { Icons } from "../icons.js";
import { escapeHtml, copyToClipboard } from "../utils.js";
import { Sound } from "../sound.js";
import { Api } from "../api.js";

export function renderSettings(state, container, handlers) {
  const { deviceName, deviceId, discoveryError, systemPaths = {} } = state;
  const currentSaveDir = localStorage.getItem("slingring_default_save_dir") || systemPaths.downloads || systemPaths.home || "";
  const isSoundEnabled = Sound.isEnabled();
  const isCompact = localStorage.getItem("slingring_compact_mode") === "true";
  const isReducedMotion = localStorage.getItem("slingring_reduced_motion") === "true";

  container.innerHTML = `
    <div class="view-settings">
      <div class="view-header">
        <div>
          <h2 class="view-title">Settings &amp; Diagnostics</h2>
          <p class="view-subtitle">Manage file storage defaults, notification preferences, and review network configuration.</p>
        </div>
      </div>

      <div class="settings-grid">
        <!-- Section: File Storage -->
        <div class="card">
          <div class="card-header">
            <div class="card-title-group">
              <div class="card-icon-pill">${Icons.folder(18)}</div>
              <div>
                <h3 class="card-title">Default Save Location</h3>
                <p class="card-subtitle">Default destination folder for accepted incoming files</p>
              </div>
            </div>
          </div>

          <div class="card-body">
            <div class="form-group">
              <label for="settings-save-dir" class="form-label">Incoming Folder Path</label>
              <div class="input-with-button">
                <input 
                  type="text" 
                  id="settings-save-dir" 
                  class="form-input font-mono" 
                  value="${escapeHtml(currentSaveDir)}"
                  placeholder="e.g. C:\\Users\\...\\Downloads"
                />
                <button class="btn btn-secondary" id="btn-browse-save-dir" type="button">
                  ${Icons.folder(16)}
                  <span>Browse…</span>
                </button>
              </div>
            </div>

            <!-- Quick Path Presets -->
            <div class="quick-chips-group">
              <span class="chips-label">Presets:</span>
              ${systemPaths.downloads ? `
                <button class="chip-btn" data-path="${escapeHtml(systemPaths.downloads)}">
                  Downloads
                </button>
              ` : ''}
              ${systemPaths.desktop ? `
                <button class="chip-btn" data-path="${escapeHtml(systemPaths.desktop)}">
                  Desktop
                </button>
              ` : ''}
              ${systemPaths.documents ? `
                <button class="chip-btn" data-path="${escapeHtml(systemPaths.documents)}">
                  Documents
                </button>
              ` : ''}
            </div>

            <div class="mt-4">
              <button class="btn btn-primary btn-sm" id="btn-save-dir-pref">
                ${Icons.check(14)}
                <span>Save Preference</span>
              </button>
            </div>
          </div>
        </div>

        <!-- Section: Audio & UI Preferences -->
        <div class="card">
          <div class="card-header">
            <div class="card-title-group">
              <div class="card-icon-pill">${Icons.settings(18)}</div>
              <div>
                <h3 class="card-title">Preferences</h3>
                <p class="card-subtitle">Micro-interactions and interface options</p>
              </div>
            </div>
          </div>

          <div class="card-body">
            <div class="settings-toggle-row">
              <div class="toggle-text">
                <strong>Sound Effects</strong>
                <span>Play subtle synthesized audio feedback when files arrive or transfers finish</span>
              </div>
              <div class="toggle-action">
                <button class="btn btn-ghost btn-sm" id="btn-test-sound" type="button" title="Test audio">
                  Test Sound
                </button>
                <label class="switch">
                  <input type="checkbox" id="toggle-sound" ${isSoundEnabled ? 'checked' : ''} />
                  <span class="slider"></span>
                </label>
              </div>
            </div>

            <div class="settings-toggle-row">
              <div class="toggle-text">
                <strong>Compact Density</strong>
                <span>Reduces padding for tables and cards to view more content at once</span>
              </div>
              <div class="toggle-action">
                <label class="switch">
                  <input type="checkbox" id="toggle-compact" ${isCompact ? 'checked' : ''} />
                  <span class="slider"></span>
                </label>
              </div>
            </div>

            <div class="settings-toggle-row">
              <div class="toggle-text">
                <strong>Reduce Motion</strong>
                <span>Disable animations for faster rendering and accessibility</span>
              </div>
              <div class="toggle-action">
                <label class="switch">
                  <input type="checkbox" id="toggle-motion" ${isReducedMotion ? 'checked' : ''} />
                  <span class="slider"></span>
                </label>
              </div>
            </div>
          </div>
        </div>

        <!-- Section: Network & Diagnostics -->
        <div class="card">
          <div class="card-header">
            <div class="card-title-group">
              <div class="card-icon-pill">${Icons.wifi(18)}</div>
              <div>
                <h3 class="card-title">Device &amp; Network Diagnostics</h3>
                <p class="card-subtitle">Real-time system identifiers and protocol status</p>
              </div>
            </div>
          </div>

          <div class="card-body">
            <div class="diag-table">
              <div class="diag-row">
                <span class="diag-label">Device Name:</span>
                <span class="diag-value"><strong>${escapeHtml(deviceName)}</strong></span>
              </div>
              <div class="diag-row">
                <span class="diag-label">Device ID:</span>
                <span class="diag-value font-mono flex items-center gap-2">
                  <span>${escapeHtml(deviceId)}</span>
                  <button class="btn-copy-hash" id="btn-copy-device-id" title="Copy Device ID">
                    ${Icons.copy(14)}
                  </button>
                </span>
              </div>
              <div class="diag-row">
                <span class="diag-label">Discovery Protocol:</span>
                <span class="diag-value">mDNS (Multicast DNS) <code>_slingring._tcp.local.</code></span>
              </div>
              <div class="diag-row">
                <span class="diag-label">Discovery Health:</span>
                <span class="diag-value">
                  ${discoveryError ? `
                    <span class="badge badge-warning">${escapeHtml(discoveryError)}</span>
                  ` : `
                    <span class="badge badge-success">Active &amp; Broadcasting</span>
                  `}
                </span>
              </div>
              <div class="diag-row">
                <span class="diag-label">API Security:</span>
                <span class="diag-value">
                  <span class="badge badge-info">Loopback Authenticated (Bearer Token)</span>
                </span>
              </div>
            </div>
          </div>
        </div>

        <!-- Section: About Sling Ring -->
        <div class="card">
          <div class="card-header">
            <div class="card-title-group">
              <div class="card-icon-pill">${Icons.shield(18)}</div>
              <div>
                <h3 class="card-title">About Sling Ring</h3>
                <p class="card-subtitle">Softablitz Hackathon Desktop Application</p>
              </div>
            </div>
          </div>

          <div class="card-body">
            <p class="text-secondary text-sm mb-3">
              <strong>Sling Ring v1.0.0</strong> is a high-performance local network file transfer application engineered for speed, privacy, and technical simplicity.
            </p>
            <ul class="feature-bullets text-sm text-secondary">
              <li>Direct HTTP streaming between peers on the same local subnet</li>
              <li>Dual-sided SHA-256 cryptographic checksum calculation &amp; verification</li>
              <li>Two-phase consent approval: metadata sent first, bytes stream only after receiver accepts</li>
              <li>Zero cloud staging, zero third-party dependencies, completely private</li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  `;

  bindEvents();

  function bindEvents() {
    // Quick chips
    container.querySelectorAll(".chip-btn").forEach(chip => {
      chip.addEventListener("click", () => {
        const path = chip.getAttribute("data-path");
        const input = container.querySelector("#settings-save-dir");
        if (input && path) {
          input.value = path;
        }
      });
    });

    // Browse native directory
    container.querySelector("#btn-browse-save-dir")?.addEventListener("click", async () => {
      try {
        const res = await Api.browseDirNative();
        if (res && res.selected && res.path) {
          const input = container.querySelector("#settings-save-dir");
          if (input) input.value = res.path;
          handlers.notify("Directory selected: " + res.path, "info");
        }
      } catch (err) {
        handlers.notify(err.message, "error");
      }
    });

    // Save save-dir preference
    container.querySelector("#btn-save-dir-pref")?.addEventListener("click", async () => {
      const input = container.querySelector("#settings-save-dir");
      const path = input?.value?.trim();
      if (!path) return;
      try {
        const res = await Api.validateDir(path);
        if (res && res.valid) {
          localStorage.setItem("slingring_default_save_dir", res.path);
          handlers.notify("Default save directory saved and verified.", "success");
        } else {
          handlers.notify((res && res.error) || "Invalid folder path", "error");
        }
      } catch (err) {
        handlers.notify(err.message, "error");
      }
    });

    // Audio test and toggle
    container.querySelector("#btn-test-sound")?.addEventListener("click", () => {
      Sound.playSuccess();
    });

    container.querySelector("#toggle-sound")?.addEventListener("change", (e) => {
      Sound.setEnabled(e.target.checked);
      handlers.notify(e.target.checked ? "Audio effects enabled" : "Audio effects muted", "info");
      if (e.target.checked) Sound.playNotice();
    });

    // Compact toggle
    container.querySelector("#toggle-compact")?.addEventListener("change", (e) => {
      localStorage.setItem("slingring_compact_mode", e.target.checked ? "true" : "false");
      document.body.classList.toggle("compact-density", e.target.checked);
      handlers.notify(e.target.checked ? "Compact density enabled" : "Normal density restored", "info");
    });

    // Motion toggle
    container.querySelector("#toggle-motion")?.addEventListener("change", (e) => {
      localStorage.setItem("slingring_reduced_motion", e.target.checked ? "true" : "false");
      document.body.classList.toggle("reduced-motion", e.target.checked);
      handlers.notify(e.target.checked ? "Reduced motion enabled" : "Animations enabled", "info");
    });

    // Copy Device ID
    container.querySelector("#btn-copy-device-id")?.addEventListener("click", async () => {
      if (deviceId) {
        await copyToClipboard(deviceId);
        handlers.notify("Device ID copied to clipboard", "success");
      }
    });
  }
}

