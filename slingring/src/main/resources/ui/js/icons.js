/**
 * SVG Icons library for Sling Ring (Linear & Lucide inspired vector set).
 * Zero external font or CDN dependencies. Fully responsive and themeable.
 */

const createSvg = (inner, className = "icon", size = 20) => `
  <svg class="${className}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    ${inner}
  </svg>
`;

export const Icons = {
  logo: (size = 28) => `
    <svg class="brand-logo-svg" width="${size}" height="${size}" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <defs>
        <linearGradient id="ringGrad" x1="2" y1="2" x2="30" y2="30" gradientUnits="userSpaceOnUse">
          <stop stop-color="#8B7CFF" />
          <stop offset="1" stop-color="#63B3FF" />
        </linearGradient>
      </defs>
      <circle cx="16" cy="16" r="13" stroke="url(#ringGrad)" stroke-width="2.75" stroke-dasharray="72 10" />
      <circle cx="16" cy="16" r="7" stroke="#F4F5FA" stroke-width="2" opacity="0.9" />
      <circle cx="16" cy="6" r="2.5" fill="#63B3FF" />
      <circle cx="26" cy="16" r="2.5" fill="#8B7CFF" />
    </svg>
  `,
  dashboard: (size) => createSvg(`
    <rect x="3" y="3" width="7" height="9" rx="1.5" />
    <rect x="14" y="3" width="7" height="5" rx="1.5" />
    <rect x="14" y="12" width="7" height="9" rx="1.5" />
    <rect x="3" y="16" width="7" height="5" rx="1.5" />
  `, "icon", size),
  send: (size) => createSvg(`
    <line x1="22" y1="2" x2="11" y2="13" />
    <polygon points="22 2 15 22 11 13 2 9 22 2" />
  `, "icon", size),
  incoming: (size) => createSvg(`
    <polyline points="22 12 16 12 14 15 10 15 8 12 2 12" />
    <path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />
  `, "icon", size),
  transfers: (size) => createSvg(`
    <polyline points="17 1 21 5 17 9" />
    <path d="M3 11V9a4 4 0 0 1 4-4h14" />
    <polyline points="7 23 3 19 7 15" />
    <path d="M21 13v2a4 4 0 0 1-4 4H3" />
  `, "icon", size),
  history: (size) => createSvg(`
    <circle cx="12" cy="12" r="10" />
    <polyline points="12 6 12 12 16 14" />
  `, "icon", size),
  settings: (size) => createSvg(`
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
  `, "icon", size),
  devices: (size) => createSvg(`
    <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
    <line x1="8" y1="21" x2="16" y2="21" />
    <line x1="12" y1="17" x2="12" y2="21" />
  `, "icon", size),
  laptop: (size) => createSvg(`
    <rect x="2" y="4" width="20" height="12" rx="2" />
    <line x1="2" y1="20" x2="22" y2="20" />
  `, "icon", size),
  file: (size) => createSvg(`
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <polyline points="14 2 14 8 20 8" />
  `, "icon", size),
  folder: (size) => createSvg(`
    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
  `, "icon", size),
  upload: (size) => createSvg(`
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <polyline points="17 8 12 3 7 8" />
    <line x1="12" y1="3" x2="12" y2="15" />
  `, "icon", size),
  check: (size) => createSvg(`
    <polyline points="20 6 9 17 4 12" />
  `, "icon", size),
  x: (size) => createSvg(`
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  `, "icon", size),
  refresh: (size) => createSvg(`
    <polyline points="23 4 23 10 17 10" />
    <polyline points="1 20 1 14 7 14" />
    <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
  `, "icon", size),
  wifi: (size) => createSvg(`
    <path d="M5 12.55a11 11 0 0 1 14.08 0" />
    <path d="M1.42 9a16 16 0 0 1 21.16 0" />
    <path d="M8.53 16.11a6 6 0 0 1 6.95 0" />
    <line x1="12" y1="20" x2="12.01" y2="20" />
  `, "icon", size),
  radar: (size) => createSvg(`
    <circle cx="12" cy="12" r="10" />
    <path d="M12 2a10 10 0 0 1 10 10" />
    <path d="M12 6a6 6 0 0 1 6 6" />
    <circle cx="12" cy="12" r="2" />
  `, "icon", size),
  arrowUpRight: (size) => createSvg(`
    <line x1="7" y1="17" x2="17" y2="7" />
    <polyline points="7 7 17 7 17 17" />
  `, "icon", size),
  arrowDownLeft: (size) => createSvg(`
    <line x1="17" y1="7" x2="7" y2="17" />
    <polyline points="17 17 7 17 7 7" />
  `, "icon", size),
  shield: (size) => createSvg(`
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
  `, "icon", size),
  copy: (size) => createSvg(`
    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  `, "icon", size),
  search: (size) => createSvg(`
    <circle cx="11" cy="11" r="8" />
    <line x1="21" y1="21" x2="16.65" y2="16.65" />
  `, "icon", size),
  clock: (size) => createSvg(`
    <circle cx="12" cy="12" r="10" />
    <polyline points="12 6 12 12 14 14" />
  `, "icon", size),
  alertCircle: (size) => createSvg(`
    <circle cx="12" cy="12" r="10" />
    <line x1="12" y1="8" x2="12" y2="12" />
    <line x1="12" y1="16" x2="12.01" y2="16" />
  `, "icon", size),
  plus: (size) => createSvg(`
    <line x1="12" y1="5" x2="12" y2="19" />
    <line x1="5" y1="12" x2="19" y2="12" />
  `, "icon", size),
  hash: (size) => createSvg(`
    <line x1="4" y1="9" x2="20" y2="9" />
    <line x1="4" y1="15" x2="20" y2="15" />
    <line x1="10" y1="3" x2="8" y2="21" />
    <line x1="16" y1="3" x2="14" y2="21" />
  `, "icon", size),
  info: (size) => createSvg(`
    <circle cx="12" cy="12" r="10" />
    <line x1="12" y1="16" x2="12" y2="12" />
    <line x1="12" y1="8" x2="12.01" y2="8" />
  `, "icon", size),
  ban: (size) => createSvg(`
    <circle cx="12" cy="12" r="10" />
    <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
  `, "icon", size)
};

