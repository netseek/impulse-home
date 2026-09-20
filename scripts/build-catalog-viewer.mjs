import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SNAP_DIR = path.join(ROOT, 'snapshots');
const catalogPath = path.join(SNAP_DIR, 'catalog.json');
const catalog = JSON.parse(fs.readFileSync(catalogPath, 'utf8'));

const htmlContent = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Haval H6 3D — Visual Review & Feedback Studio</title>
  <style>
    :root {
      --bg: #0b0f14;
      --card-bg: #141b22;
      --card-hover: #1b242e;
      --border: #23303d;
      --border-focus: #374b5e;
      --accent: #2aa7b7;
      --accent-bright: #3ed1e3;
      --text: #f0f6fc;
      --muted: #8b949e;
      --crit: #f85149;
      --crit-bg: rgba(248, 81, 73, 0.12);
      --mod: #d29922;
      --mod-bg: rgba(210, 153, 34, 0.12);
      --clean: #3fb950;
      --clean-bg: rgba(63, 185, 80, 0.12);
      --badge-bg: #1f2a36;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      padding: 24px 32px 100px;
      line-height: 1.5;
    }

    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--border);
      padding-bottom: 20px;
      margin-bottom: 22px;
      flex-wrap: wrap;
      gap: 16px;
    }
    .header-left h1 {
      font-size: 24px;
      font-weight: 700;
      letter-spacing: -0.02em;
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .header-left .pill {
      font-size: 11px;
      padding: 3px 9px;
      border-radius: 20px;
      background: rgba(42, 167, 183, 0.15);
      color: var(--accent);
      font-weight: 600;
      border: 1px solid var(--accent);
    }
    .meta { font-size: 13px; color: var(--muted); margin-top: 4px; }
    .header-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }

    .btn {
      background: var(--card-bg);
      border: 1px solid var(--border);
      color: var(--text);
      padding: 8px 16px;
      border-radius: 8px;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      transition: all 0.2s;
    }
    .btn:hover { border-color: var(--accent); background: var(--card-hover); }
    .btn-primary { background: var(--accent); color: #000; border-color: var(--accent); }
    .btn-primary:hover { background: var(--accent-bright); color: #000; }
    .btn.active { background: var(--accent); color: #000; border-color: var(--accent); }
    .btn-sm {
      padding: 6px 12px;
      font-size: 12px;
      border-radius: 6px;
    }

    /* Stats Strip */
    .stats-strip {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      gap: 12px;
      margin-bottom: 22px;
    }
    .stat-card {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 10px 16px;
      display: flex;
      flex-direction: column;
    }
    .stat-card strong { font-size: 20px; font-weight: 700; color: var(--accent); }
    .stat-card.crit strong { color: var(--crit); }
    .stat-card.clean strong { color: var(--clean); }
    .stat-card span { font-size: 11px; color: var(--muted); text-transform: uppercase; margin-top: 2px; }

    /* Controls Bar */
    .controls-bar {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 16px 20px;
      margin-bottom: 24px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .nav-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;
    }
    .nav-tabs {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .tab-btn {
      background: transparent;
      border: 1px solid var(--border);
      color: var(--muted);
      padding: 7px 14px;
      border-radius: 6px;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.15s;
    }
    .tab-btn:hover { color: var(--text); border-color: var(--border-focus); }
    .tab-btn.active { background: var(--accent); color: #000; border-color: var(--accent); }

    .domain-pills {
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
      border-top: 1px solid var(--border);
      padding-top: 12px;
    }
    .domain-pill {
      background: #0d1217;
      border: 1px solid var(--border);
      color: var(--muted);
      padding: 6px 12px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      transition: all 0.15s;
    }
    .domain-pill:hover { border-color: var(--accent); color: var(--text); }
    .domain-pill.active {
      background: rgba(42, 167, 183, 0.2);
      border-color: var(--accent);
      color: var(--accent-bright);
      font-weight: 600;
    }

    .filters-row {
      display: flex;
      gap: 10px;
      align-items: center;
      flex-wrap: wrap;
    }
    .search-input, .select-filter {
      background: #0d1217;
      border: 1px solid var(--border);
      color: var(--text);
      padding: 7px 12px;
      border-radius: 6px;
      font-size: 13px;
    }
    .search-input { min-width: 240px; flex: 1; }
    .search-input:focus, .select-filter:focus { outline: none; border-color: var(--accent); }

    /* Domain Section Grouping */
    .domain-section {
      margin-bottom: 36px;
      background: rgba(20, 27, 34, 0.4);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 22px 24px;
    }
    .domain-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--border);
      padding-bottom: 14px;
      margin-bottom: 16px;
      flex-wrap: wrap;
      gap: 12px;
    }
    .domain-header-left {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
    .domain-title {
      font-size: 18px;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: 10px;
      color: var(--accent-bright);
    }
    .domain-count {
      font-size: 11px;
      color: var(--muted);
      background: var(--card-bg);
      border: 1px solid var(--border);
      padding: 2px 8px;
      border-radius: 10px;
      font-weight: 600;
    }
    .domain-header-actions {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }

    /* Group-Level Comment Banner */
    .group-comment-banner {
      background: #10161d;
      border: 1px solid var(--border);
      border-left: 4px solid var(--accent);
      border-radius: 10px;
      padding: 14px 18px;
      margin-bottom: 20px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .group-comment-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 13px;
      font-weight: 600;
      color: var(--accent-bright);
    }
    .group-comment-box {
      width: 100%;
      background: #080c10;
      border: 1px solid var(--border);
      border-radius: 6px;
      color: var(--text);
      font-family: inherit;
      font-size: 13px;
      padding: 10px 12px;
      resize: vertical;
      min-height: 56px;
      line-height: 1.45;
    }
    .group-comment-box:focus {
      outline: none;
      border-color: var(--accent);
      box-shadow: 0 0 0 2px rgba(42, 167, 183, 0.2);
    }
    .saved-pill {
      font-size: 11px;
      color: var(--muted);
      font-weight: 500;
    }
    .saved-pill.saved {
      color: var(--clean);
      font-weight: 600;
    }

    /* Grid Layout */
    .gallery-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
      gap: 18px;
    }
    .gallery-item {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 12px;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      transition: transform 0.15s, border-color 0.15s;
    }
    .gallery-item:hover {
      border-color: var(--accent);
      transform: translateY(-2px);
    }
    .gallery-item.warn-border { border-top: 3px solid var(--crit); }
    .item-header {
      padding: 10px 14px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--border);
      background: rgba(255, 255, 255, 0.02);
    }
    .item-title { font-size: 13px; font-weight: 600; }
    .item-badge {
      font-size: 11px;
      background: var(--badge-bg);
      padding: 2px 7px;
      border-radius: 4px;
      border: 1px solid var(--border);
      color: var(--accent);
      font-family: monospace;
      font-weight: 600;
    }
    .item-badge.badge-card { color: #58a6ff; border-color: rgba(56, 139, 253, 0.4); }
    .item-badge.badge-popup { color: #bc8cff; border-color: rgba(187, 128, 179, 0.4); }
    .item-badge.badge-crit { color: var(--crit); border-color: var(--crit); background: var(--crit-bg); }

    .img-wrap {
      padding: 14px;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 180px;
      background: #070a0e;
      cursor: pointer;
      position: relative;
    }
    .img-wrap:hover .zoom-hint { opacity: 1; }
    .zoom-hint {
      position: absolute;
      top: 8px;
      right: 8px;
      background: rgba(0, 0, 0, 0.75);
      padding: 3px 8px;
      border-radius: 4px;
      font-size: 11px;
      color: #fff;
      opacity: 0;
      transition: opacity 0.2s;
      pointer-events: none;
    }
    .img-wrap img {
      max-width: 100%;
      max-height: 250px;
      object-fit: contain;
      border-radius: 6px;
      box-shadow: 0 4px 14px rgba(0,0,0,0.6);
    }
    .item-footer {
      padding: 10px 14px;
      font-size: 12px;
      color: var(--muted);
      border-top: 1px solid var(--border);
      margin-top: auto;
    }
    .issue-box {
      background: rgba(248, 81, 73, 0.12);
      border-left: 3px solid var(--crit);
      padding: 5px 8px;
      margin-top: 6px;
      font-size: 11px;
      color: #ff9999;
    }

    /* Feedback Area */
    .feedback-area {
      padding: 10px 14px;
      background: #0f151b;
      border-top: 1px solid var(--border);
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .feedback-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 6px;
    }
    .status-select {
      background: #0b0f13;
      border: 1px solid var(--border);
      color: var(--text);
      padding: 4px 8px;
      border-radius: 5px;
      font-size: 11px;
      font-weight: 500;
    }
    .comment-input {
      width: 100%;
      background: #0b0f13;
      border: 1px solid var(--border);
      border-radius: 5px;
      color: var(--text);
      font-family: inherit;
      font-size: 12px;
      padding: 6px 8px;
      resize: vertical;
      min-height: 48px;
    }
    .comment-input:focus, .status-select:focus { outline: none; border-color: var(--accent); }

    /* Modal / Lightbox Popup */
    .modal-overlay {
      position: fixed;
      inset: 0;
      background: rgba(4, 7, 10, 0.88);
      backdrop-filter: blur(8px);
      z-index: 10000;
      display: none;
      align-items: center;
      justify-content: center;
      padding: 20px;
    }
    .modal-overlay.active { display: flex; }
    .modal-content {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 14px;
      max-width: 92vw;
      max-height: 94vh;
      display: flex;
      flex-direction: column;
      box-shadow: 0 16px 48px rgba(0, 0, 0, 0.85);
      overflow: hidden;
      width: 1080px;
    }
    .modal-header {
      padding: 12px 20px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--border);
      background: #0d1318;
      flex-wrap: wrap;
      gap: 10px;
    }
    .modal-header h3 { font-size: 16px; font-weight: 600; display: flex; align-items: center; gap: 10px; }
    .modal-header-controls { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .modal-body {
      padding: 24px;
      background: #05080b;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 380px;
      max-height: 60vh;
      position: relative;
      overflow: auto;
    }
    .modal-body img {
      max-width: 100%;
      max-height: 56vh;
      object-fit: contain;
      border-radius: 6px;
      box-shadow: 0 6px 24px rgba(0,0,0,0.7);
    }
    .modal-nav-btn {
      position: absolute;
      top: 50%;
      transform: translateY(-50%);
      background: rgba(20, 27, 34, 0.85);
      border: 1px solid var(--border);
      color: var(--text);
      font-size: 20px;
      width: 44px;
      height: 44px;
      border-radius: 50%;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: all 0.15s;
    }
    .modal-nav-btn:hover { background: var(--accent); color: #000; }
    .modal-prev { left: 16px; }
    .modal-next { right: 16px; }
    .modal-footer {
      padding: 14px 20px;
      border-top: 1px solid var(--border);
      background: #0f151b;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .toast {
      position: fixed;
      bottom: 24px;
      right: 24px;
      background: var(--accent);
      color: #000;
      font-weight: 600;
      padding: 12px 20px;
      border-radius: 8px;
      box-shadow: 0 4px 16px rgba(0,0,0,0.6);
      opacity: 0;
      transform: translateY(10px);
      transition: all 0.25s;
      pointer-events: none;
      z-index: 20000;
    }
    .toast.show { opacity: 1; transform: translateY(0); }
  </style>
</head>
<body>
  <header>
    <div class="header-left">
      <h1>
        Haval H6 3D — Visual Review & Feedback Studio
        <span class="pill">Group-by-Group Workflow</span>
      </h1>
      <div class="meta">
        Organized by functional domain • Provide overall group comments, review individual surfaces, and copy group-specific adjustment reports.
      </div>
    </div>
    <div class="header-actions">
      <button class="btn btn-primary" id="btnHeaderCopy" onclick="copyHeaderReport()">📋 Copy Group Report</button>
      <button class="btn" onclick="exportFeedbackJson()">💾 Export JSON</button>
    </div>
  </header>

  <section class="stats-strip">
    <div class="stat-card">
      <strong id="statTotal">88</strong>
      <span>Total Surfaces</span>
    </div>
    <div class="stat-card">
      <strong>13</strong>
      <span>Cards</span>
    </div>
    <div class="stat-card">
      <strong>14</strong>
      <span>Popups</span>
    </div>
    <div class="stat-card">
      <strong>61</strong>
      <span>Widgets</span>
    </div>
    <div class="stat-card crit">
      <strong id="statFlagged">41</strong>
      <span>Flagged Issues</span>
    </div>
    <div class="stat-card clean">
      <strong id="userReviewCount">0</strong>
      <span>Feedback Saved</span>
    </div>
  </section>

  <section class="controls-bar">
    <div class="nav-row">
      <div class="nav-tabs">
        <button class="tab-btn active" onclick="setTab('all')">All Surfaces (88)</button>
        <button class="tab-btn" onclick="setTab('cards')">Launcher Cards (13)</button>
        <button class="tab-btn" onclick="setTab('popups')">Workspace Popups (14)</button>
        <button class="tab-btn" onclick="setTab('widgets')">Widgets (61)</button>
        <button class="tab-btn" onclick="setTab('flagged')">⚠️ Flagged Issues (41)</button>
        <button class="tab-btn" onclick="setTab('reviewed')">💬 My Feedback (<span id="userReviewTabCount">0</span>)</button>
      </div>

      <div style="display: flex; gap: 8px;">
        <button class="btn active" id="btnGroupView" onclick="toggleGrouping(true)">📑 Group by Domain</button>
        <button class="btn" id="btnGridView" onclick="toggleGrouping(false)">🔲 Flat Grid</button>
      </div>
    </div>

    <!-- Domain Filter Pills -->
    <div class="domain-pills" id="domainPills">
      <button class="domain-pill active" onclick="setDomain('all')">All Domains (12 Groups)</button>
      <button class="domain-pill" onclick="setDomain('energy')">⚡ Energy & Consumption</button>
      <button class="domain-pill" onclick="setDomain('range')">🔋 Battery & Range</button>
      <button class="domain-pill" onclick="setDomain('navigation')">🗺️ Navigation</button>
      <button class="domain-pill" onclick="setDomain('climate')">❄️ Climate & Comfort</button>
      <button class="domain-pill" onclick="setDomain('status')">🚘 Vehicle Status & Roof</button>
      <button class="domain-pill" onclick="setDomain('tires')">🛞 Tire Pressure</button>
      <button class="domain-pill" onclick="setDomain('driving')">🏎️ Driving Modes & Regen</button>
      <button class="domain-pill" onclick="setDomain('power')">⚡ Power Flow & Graphs</button>
      <button class="domain-pill" onclick="setDomain('media')">🎵 Media & Audio</button>
      <button class="domain-pill" onclick="setDomain('clock')">🕒 Clock & Time</button>
      <button class="domain-pill" onclick="setDomain('profile')">👤 Profile & Themes</button>
      <button class="domain-pill" onclick="setDomain('system')">📱 Layout & System</button>
    </div>

    <div class="filters-row">
      <input type="text" id="searchInput" class="search-input" placeholder="🔍 Search by name, title, note, or comment..." oninput="applyFilters()">

      <select id="typeFilter" class="select-filter" onchange="applyFilters()">
        <option value="">All Widget Types</option>
        <option value="profile">Profile</option>
        <option value="clock">Clock</option>
        <option value="navigation">Navigation</option>
        <option value="media">Media</option>
        <option value="tires">Tires</option>
        <option value="driving">Driving</option>
        <option value="status">Status</option>
        <option value="range">Range</option>
        <option value="power">Power</option>
        <option value="graphs">Graphs</option>
        <option value="climate">Climate</option>
        <option value="consumption">Consumption</option>
      </select>

      <select id="sizeFilter" class="select-filter" onchange="applyFilters()">
        <option value="">All Sizes</option>
        <option value="1x1">1×1</option>
        <option value="1x2">1×2</option>
        <option value="2x1">2×1</option>
        <option value="2x2">2×2</option>
        <option value="3x1">3×1</option>
        <option value="3x2">3×2</option>
      </select>

      <select id="statusFilter" class="select-filter" onchange="applyFilters()">
        <option value="">All Review Statuses</option>
        <option value="pending">Pending</option>
        <option value="approved">Approved ✅</option>
        <option value="polish">Needs Polish ⚠️</option>
        <option value="critical">Critical Fix ❌</option>
        <option value="redesign">Redesign 🎨</option>
      </select>
    </div>
  </section>

  <!-- Container for rendered items -->
  <main id="mainContainer"></main>

  <!-- In-Page Lightbox Popup Modal -->
  <div class="modal-overlay" id="lightboxModal" onclick="closeLightbox(event)">
    <div class="modal-content" onclick="event.stopPropagation()">
      <div class="modal-header">
        <h3 id="modalTitle">
          <span class="item-badge" id="modalBadge"></span>
          <span id="modalName">Snapshot Title</span>
        </h3>
        <div class="modal-header-controls">
          <button class="btn btn-sm btn-primary" id="modalCopyGroupBtn" onclick="copyCurrentModalGroupReport()">📋 Copy Group Report</button>
          <a id="modalNewTab" href="" target="_blank" class="btn btn-sm">Open Full Res ↗</a>
          <button class="btn btn-sm" onclick="closeLightbox()">✕ Close</button>
        </div>
      </div>
      <div class="modal-body">
        <button class="modal-nav-btn modal-prev" onclick="prevModalItem()">◀</button>
        <img id="modalImg" src="" alt="Snapshot Enlarged">
        <button class="modal-nav-btn modal-next" onclick="nextModalItem()">▶</button>
      </div>
      <div class="modal-footer">
        <div style="display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--muted); flex-wrap: wrap; gap: 8px;">
          <div id="modalMeta">549 × 468 px • Climate Workspace</div>
          <div id="modalWarn" style="color: var(--crit); font-weight: 600;"></div>
        </div>
        <div class="feedback-row">
          <span style="font-size: 12px; font-weight: 600; color: var(--muted);">REVIEW STATUS:</span>
          <select id="modalStatusSelect" class="status-select" onchange="onModalStatusChange(this.value)">
            <option value="pending">Pending</option>
            <option value="approved">Approved ✅</option>
            <option value="polish">Needs Polish ⚠️</option>
            <option value="critical">Critical Fix ❌</option>
            <option value="redesign">Redesign 🎨</option>
          </select>
        </div>
        <textarea id="modalCommentBox" class="comment-input" placeholder="Type adjustment instructions or feedback for this surface..." oninput="onModalCommentInput(this.value)"></textarea>
      </div>
    </div>
  </div>

  <div class="toast" id="toastNotification">Feedback copied to clipboard!</div>

  <script>
    const data = ${JSON.stringify(catalog)};

    // Domain definitions mapping functional cards, popups, and widgets
    const DOMAINS = [
      { id: 'energy', label: 'Energy & Consumption', icon: '⚡', cards: ['consumption'], popups: ['consumption'], widgets: ['consumption'] },
      { id: 'range', label: 'Battery & Range', icon: '🔋', cards: ['range'], popups: ['range'], widgets: ['range'] },
      { id: 'navigation', label: 'Navigation', icon: '🗺️', cards: ['navigation'], popups: [], widgets: ['navigation'] },
      { id: 'climate', label: 'Climate & Comfort', icon: '❄️', cards: ['climate'], popups: ['climate'], widgets: ['climate'] },
      { id: 'status', label: 'Vehicle Status & Roof', icon: '🚘', cards: ['status'], popups: ['status', 'roof'], widgets: ['status'] },
      { id: 'tires', label: 'Tire Pressure', icon: '🛞', cards: [], popups: ['tires'], widgets: ['tires'] },
      { id: 'driving', label: 'Driving Modes & Regen', icon: '🏎️', cards: ['driveMode', 'powerMode', 'regen'], popups: ['driving'], widgets: ['driving'] },
      { id: 'power', label: 'Power Flow & Graphs', icon: '⚡', cards: ['power'], popups: ['power'], widgets: ['power', 'graphs'] },
      { id: 'media', label: 'Media & Audio', icon: '🎵', cards: ['media'], popups: ['media'], widgets: ['media'] },
      { id: 'clock', label: 'Clock & Time', icon: '🕒', cards: ['clock'], popups: ['studio-clock'], widgets: ['clock'] },
      { id: 'profile', label: 'Profile & Themes', icon: '👤', cards: [], popups: [], widgets: ['profile'] },
      { id: 'system', label: 'Layout Manager & Rail', icon: '📱', cards: ['desktops', 'rail_edit_mode'], popups: ['studio-desktops', 'studio-cards', 'studio-widgets', 'studio-appearance'], widgets: [] }
    ];

    function getDomainFor(kind, id, type) {
      for (const d of DOMAINS) {
        if (kind === 'card' && d.cards.includes(id)) return d.id;
        if (kind === 'popup' && d.popups.includes(id)) return d.id;
        if (kind === 'widget' && d.widgets.includes(type)) return d.id;
      }
      return 'system';
    }

    // Build unified item list for modal navigation and indexing
    const allItems = [];

    data.cards.forEach(c => {
      allItems.push({
        uid: 'card_' + c.id,
        kind: 'card',
        id: c.id,
        type: c.id,
        title: c.title || c.id.toUpperCase(),
        domain: getDomainFor('card', c.id, c.id),
        size: 'Rail Card',
        badge: 'CARD',
        badgeClass: 'badge-card',
        image: c.image,
        fullImage: 'fullscreen/rail_' + (['clock', 'status', 'range', 'media', 'navigation'].includes(c.id) ? 'batch1' : (['climate', 'consumption', 'power', 'driveMode', 'powerMode'].includes(c.id) ? 'batch2' : 'batch3')) + '.png',
        dim: c.bounds ? (c.bounds.width + ' × ' + c.bounds.height + ' px') : '124px',
        overflowCount: 0,
        desc: 'Native launcher rail card'
      });
    });

    data.popups.forEach(p => {
      allItems.push({
        uid: 'popup_' + p.id,
        kind: 'popup',
        id: p.id,
        type: p.id,
        title: p.name,
        domain: getDomainFor('popup', p.id, p.id),
        size: 'Popup',
        badge: 'POPUP',
        badgeClass: 'badge-popup',
        image: p.image,
        fullImage: p.fullImage,
        dim: p.rect ? (Math.round(p.rect.width) + ' × ' + Math.round(p.rect.height) + ' px') : 'Popup',
        overflowCount: p.overflowCount || 0,
        desc: 'Floating workspace popup'
      });
    });

    data.widgets.forEach(w => {
      allItems.push({
        uid: 'widget_' + w.type + '_' + w.size,
        kind: 'widget',
        id: w.type + '_' + w.size,
        type: w.type,
        size: w.size,
        title: w.label + ' (' + w.size + ')',
        domain: getDomainFor('widget', w.type, w.type),
        badge: w.size,
        badgeClass: w.overflowCount > 0 ? 'badge-crit' : '',
        image: w.image,
        fullImage: w.image,
        dim: w.rect ? (Math.round(w.rect.width) + ' × ' + Math.round(w.rect.height) + ' px') : w.size,
        overflowCount: w.overflowCount || 0,
        desc: w.title || (w.size + ' Grid Cell')
      });
    });

    const STORAGE_KEY = 'h6_visual_feedback_v1';
    const GROUP_STORAGE_KEY = 'h6_group_feedback_v1';

    let userFeedback = {};
    let groupFeedback = {};

    try {
      userFeedback = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
    } catch {}

    try {
      groupFeedback = JSON.parse(localStorage.getItem(GROUP_STORAGE_KEY) || '{}');
    } catch {}

    let currentTab = 'all';
    let currentDomain = 'all';
    let isGrouped = true; // Default to group-by-domain
    let currentModalIndex = -1;

    function escapeHtml(str) {
      if (!str) return '';
      return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    }

    function saveFeedback(uid, status, comment) {
      if (!userFeedback[uid]) userFeedback[uid] = { status: 'pending', comment: '' };
      if (status !== undefined) userFeedback[uid].status = status;
      if (comment !== undefined) userFeedback[uid].comment = comment;
      localStorage.setItem(STORAGE_KEY, JSON.stringify(userFeedback));
      updateReviewCounts();
    }

    function onGroupCommentInput(domainId, val) {
      groupFeedback[domainId] = val;
      localStorage.setItem(GROUP_STORAGE_KEY, JSON.stringify(groupFeedback));
      const statusEl = document.getElementById('group-status-' + domainId);
      if (statusEl) {
        statusEl.innerText = val.trim() ? '✓ Saved' : 'Auto-saves to browser';
        statusEl.classList.toggle('saved', !!val.trim());
      }
      updateReviewCounts();
    }

    function updateReviewCounts() {
      const activeItemCount = Object.values(userFeedback).filter(v => (v.comment && v.comment.trim()) || (v.status && v.status !== 'pending')).length;
      const activeGroupCount = Object.values(groupFeedback).filter(v => v && v.trim()).length;
      const totalFeedback = activeItemCount + activeGroupCount;
      document.getElementById('userReviewCount').innerText = totalFeedback;
      document.getElementById('userReviewTabCount').innerText = activeItemCount;
      updateHeaderCopyButton();
    }

    function updateHeaderCopyButton() {
      const copyBtn = document.getElementById('btnHeaderCopy');
      if (!copyBtn) return;
      if (currentDomain !== 'all') {
        const d = DOMAINS.find(x => x.id === currentDomain);
        copyBtn.innerText = '📋 Copy ' + (d ? d.label : 'Group') + ' Report';
      } else {
        copyBtn.innerText = '📋 Copy Full Report (All Groups)';
      }
    }

    function renderCard(item) {
      const fb = userFeedback[item.uid] || { status: 'pending', comment: '' };
      const isWarn = item.overflowCount > 0;
      const borderClass = isWarn ? 'warn-border' : '';
      let issueHtml = '';
      if (isWarn) {
        issueHtml = '<div class="issue-box">⚠️ ' + item.overflowCount + ' layout overflow / clipping warnings.</div>';
      }

      return \`
        <div class="gallery-item \${borderClass}" id="card-\${item.uid}" data-uid="\${item.uid}" data-kind="\${item.kind}" data-domain="\${item.domain}" data-type="\${item.type}" data-size="\${item.size || ''}" data-warn="\${isWarn}" data-status="\${fb.status}">
          <div class="item-header">
            <span class="item-title">\${item.title}</span>
            <span class="item-badge \${item.badgeClass}">\${item.badge}</span>
          </div>
          <div class="img-wrap" onclick="openLightbox('\${item.uid}')" title="Click to open popup preview">
            <span class="zoom-hint">🔍 View Popup</span>
            <img src="\${item.image}" alt="\${item.title}" loading="lazy">
          </div>
          <div class="item-footer">
            <div style="font-size: 11px; color: var(--muted);">\${item.dim} • \${item.desc}</div>
            \${issueHtml}
          </div>
          <div class="feedback-area">
            <div class="feedback-row">
              <span style="font-size: 11px; font-weight: 600; color: var(--muted);">STATUS:</span>
              <select class="status-select" onchange="onItemStatusChange('\${item.uid}', this.value)">
                <option value="pending" \${fb.status === 'pending' ? 'selected' : ''}>Pending</option>
                <option value="approved" \${fb.status === 'approved' ? 'selected' : ''}>Approved ✅</option>
                <option value="polish" \${fb.status === 'polish' ? 'selected' : ''}>Needs Polish ⚠️</option>
                <option value="critical" \${fb.status === 'critical' ? 'selected' : ''}>Critical Fix ❌</option>
                <option value="redesign" \${fb.status === 'redesign' ? 'selected' : ''}>Redesign 🎨</option>
              </select>
            </div>
            <textarea class="comment-input" id="comment-\${item.uid}" placeholder="Feedback or adjustments..." oninput="onItemCommentInput('\${item.uid}', this.value)">\${escapeHtml(fb.comment || '')}</textarea>
          </div>
        </div>
      \`;
    }

    function onItemStatusChange(uid, val) {
      saveFeedback(uid, val, undefined);
    }
    function onItemCommentInput(uid, val) {
      saveFeedback(uid, undefined, val);
    }

    function renderView() {
      const container = document.getElementById('mainContainer');
      let visibleItems = allItems.filter(item => {
        if (currentTab === 'cards' && item.kind !== 'card') return false;
        if (currentTab === 'popups' && item.kind !== 'popup') return false;
        if (currentTab === 'widgets' && item.kind !== 'widget') return false;
        if (currentTab === 'flagged' && item.overflowCount === 0) return false;
        if (currentTab === 'reviewed') {
          const fb = userFeedback[item.uid];
          const hasFb = fb && ((fb.comment && fb.comment.trim()) || (fb.status && fb.status !== 'pending'));
          if (!hasFb) return false;
        }
        if (currentDomain !== 'all' && item.domain !== currentDomain) return false;
        return true;
      });

      if (!isGrouped) {
        // Flat Grid View
        let html = '';
        if (currentDomain !== 'all') {
          const d = DOMAINS.find(x => x.id === currentDomain);
          if (d) {
            const groupComment = groupFeedback[d.id] || '';
            const hasGroupComment = groupComment.trim().length > 0;
            html += \`
              <div class="group-comment-banner">
                <div class="group-comment-header">
                  <span>💭 Group Directions for \${d.label}:</span>
                  <span class="saved-pill \${hasGroupComment ? 'saved' : ''}" id="group-status-\${d.id}">
                    \${hasGroupComment ? '✓ Saved' : 'Auto-saves to browser'}
                  </span>
                </div>
                <textarea 
                  class="group-comment-box" 
                  id="group-comment-\${d.id}" 
                  placeholder="Provide overall feedback, styling goals, or broad instructions for the entire \${d.label} group..."
                  oninput="onGroupCommentInput('\${d.id}', this.value)"
                >\${escapeHtml(groupComment)}</textarea>
              </div>
            \`;
          }
        }
        html += '<div class="gallery-grid" id="gridContainer">';
        visibleItems.forEach(i => html += renderCard(i));
        html += '</div>';
        container.innerHTML = html;
      } else {
        // Grouped by Domain View
        let html = '';
        DOMAINS.forEach(d => {
          if (currentDomain !== 'all' && d.id !== currentDomain) return;
          const domainItems = visibleItems.filter(i => i.domain === d.id);
          if (domainItems.length === 0) return;

          const groupComment = groupFeedback[d.id] || '';
          const hasGroupComment = groupComment.trim().length > 0;

          html += \`
            <section class="domain-section" id="domain-sec-\${d.id}">
              <div class="domain-header">
                <div class="domain-header-left">
                  <div class="domain-title">
                    <span>\${d.icon}</span>
                    <span>\${d.label}</span>
                  </div>
                  <span class="domain-count">\${domainItems.length} Surfaces</span>
                </div>
                <div class="domain-header-actions">
                  <button class="btn btn-sm btn-primary" onclick="copyGroupReport('\${d.id}')" title="Extract and copy feedback for only \${d.label}">
                    📋 Copy \${d.label} Report
                  </button>
                  \${currentDomain !== d.id 
                    ? \`<button class="btn btn-sm" onclick="setDomain('\${d.id}')" title="Focus exclusively on this group">🎯 Focus Group</button>\` 
                    : \`<button class="btn btn-sm" onclick="setDomain('all')" title="Show all groups">🌐 Show All Groups</button>\`
                  }
                </div>
              </div>

              <div class="group-comment-banner">
                <div class="group-comment-header">
                  <span>💬 Group Instructions & Scope (\${d.label}):</span>
                  <span class="saved-pill \${hasGroupComment ? 'saved' : ''}" id="group-status-\${d.id}">
                    \${hasGroupComment ? '✓ Saved' : 'Auto-saves to browser'}
                  </span>
                </div>
                <textarea 
                  class="group-comment-box" 
                  id="group-comment-\${d.id}" 
                  placeholder="Provide overall feedback, styling goals, or broad instructions for the entire \${d.label} group (applies across cards, popups, and widgets)..."
                  oninput="onGroupCommentInput('\${d.id}', this.value)"
                >\${escapeHtml(groupComment)}</textarea>
              </div>

              <div class="gallery-grid">
                \${domainItems.map(renderCard).join('')}
              </div>
            </section>
          \`;
        });
        container.innerHTML = html || '<div style="color: var(--muted); padding: 40px; text-align: center;">No matching surfaces found.</div>';
      }

      updateReviewCounts();
      applyFilters();
    }

    function toggleGrouping(grouped) {
      isGrouped = grouped;
      document.getElementById('btnGroupView').classList.toggle('active', isGrouped);
      document.getElementById('btnGridView').classList.toggle('active', !isGrouped);
      renderView();
    }

    function setTab(tab) {
      currentTab = tab;
      document.querySelectorAll('.tab-btn').forEach(b => {
        b.classList.toggle('active', b.getAttribute('onclick').includes("'" + tab + "'"));
      });
      renderView();
    }

    function setDomain(dom) {
      currentDomain = dom;
      document.querySelectorAll('.domain-pill').forEach(b => {
        b.classList.toggle('active', b.getAttribute('onclick').includes("'" + dom + "'"));
      });
      updateHeaderCopyButton();
      renderView();
    }

    function applyFilters() {
      const q = (document.getElementById('searchInput')?.value || '').toLowerCase().trim();
      const tf = document.getElementById('typeFilter')?.value || '';
      const sf = document.getElementById('sizeFilter')?.value || '';
      const st = document.getElementById('statusFilter')?.value || '';

      document.querySelectorAll('.gallery-item').forEach(el => {
        let match = true;
        const uid = el.dataset.uid;
        const fb = userFeedback[uid] || { status: 'pending', comment: '' };

        if (tf && el.dataset.type !== tf) match = false;
        if (sf && el.dataset.size !== sf) match = false;
        if (st && fb.status !== st) match = false;

        if (q) {
          const text = el.innerText.toLowerCase();
          const comment = (fb.comment || '').toLowerCase();
          if (!text.includes(q) && !comment.includes(q)) match = false;
        }

        el.style.display = match ? 'flex' : 'none';
      });
    }

    // Modal / Lightbox Logic
    function openLightbox(uid) {
      currentModalIndex = allItems.findIndex(i => i.uid === uid);
      if (currentModalIndex === -1) return;
      updateModalContent();
      document.getElementById('lightboxModal').classList.add('active');
    }

    function closeLightbox(e) {
      if (e && e.target !== document.getElementById('lightboxModal') && !e.target.closest('.modal-header-controls')) return;
      document.getElementById('lightboxModal').classList.remove('active');
    }

    function prevModalItem() {
      if (currentModalIndex > 0) {
        currentModalIndex--;
        updateModalContent();
      } else {
        currentModalIndex = allItems.length - 1;
        updateModalContent();
      }
    }

    function nextModalItem() {
      if (currentModalIndex < allItems.length - 1) {
        currentModalIndex++;
        updateModalContent();
      } else {
        currentModalIndex = 0;
        updateModalContent();
      }
    }

    function updateModalContent() {
      const item = allItems[currentModalIndex];
      if (!item) return;

      const fb = userFeedback[item.uid] || { status: 'pending', comment: '' };
      const d = DOMAINS.find(dom => dom.id === item.domain);
      const domainLabel = d ? (d.icon + ' ' + d.label) : item.domain;

      document.getElementById('modalName').innerText = item.title;
      document.getElementById('modalBadge').innerText = item.badge;
      document.getElementById('modalBadge').className = 'item-badge ' + item.badgeClass;
      document.getElementById('modalImg').src = item.image;
      document.getElementById('modalNewTab').href = item.fullImage || item.image;
      document.getElementById('modalMeta').innerText = domainLabel + ' • ' + item.dim + ' • ' + item.desc + ' (' + (currentModalIndex + 1) + '/' + allItems.length + ')';
      
      const copyGroupBtn = document.getElementById('modalCopyGroupBtn');
      if (copyGroupBtn && d) {
        copyGroupBtn.innerText = '📋 Copy ' + d.label + ' Report';
      }

      const warnEl = document.getElementById('modalWarn');
      if (item.overflowCount > 0) {
        warnEl.innerText = '⚠️ ' + item.overflowCount + ' Layout Overflows';
        warnEl.style.color = 'var(--crit)';
      } else {
        warnEl.innerText = '✓ Clean Layout';
        warnEl.style.color = 'var(--clean)';
      }

      document.getElementById('modalStatusSelect').value = fb.status || 'pending';
      document.getElementById('modalCommentBox').value = fb.comment || '';
    }

    function copyCurrentModalGroupReport() {
      const item = allItems[currentModalIndex];
      if (item) copyGroupReport(item.domain);
    }

    function onModalStatusChange(val) {
      const item = allItems[currentModalIndex];
      if (!item) return;
      saveFeedback(item.uid, val, undefined);
      const card = document.getElementById('card-' + item.uid);
      if (card) {
        card.querySelector('.status-select').value = val;
      }
    }

    function onModalCommentInput(val) {
      const item = allItems[currentModalIndex];
      if (!item) return;
      saveFeedback(item.uid, undefined, val);
      const commentEl = document.getElementById('comment-' + item.uid);
      if (commentEl) commentEl.value = val;
    }

    document.addEventListener('keydown', (e) => {
      const modal = document.getElementById('lightboxModal');
      if (!modal.classList.contains('active')) return;
      if (e.key === 'Escape') closeLightbox({ target: modal });
      if (e.key === 'ArrowLeft') prevModalItem();
      if (e.key === 'ArrowRight') nextModalItem();
    });

    function showToast(msg) {
      const t = document.getElementById('toastNotification');
      t.innerText = msg;
      t.classList.add('show');
      setTimeout(() => t.classList.remove('show'), 3200);
    }

    // Group-specific report generator
    function copyGroupReport(domainId) {
      const d = DOMAINS.find(dom => dom.id === domainId);
      if (!d) return;

      const domainItems = allItems.filter(i => i.domain === domainId);
      const groupComment = (groupFeedback[domainId] || '').trim();
      
      const itemFeedbackList = domainItems.filter(item => {
        const fb = userFeedback[item.uid];
        return fb && ((fb.comment && fb.comment.trim()) || (fb.status && fb.status !== 'pending'));
      });

      const overflowItems = domainItems.filter(i => i.overflowCount > 0);

      let report = '# [' + d.label + ' Group Feedback]\\n\\n';
      report += '### Functional Group: ' + d.icon + ' ' + d.label + ' (' + domainItems.length + ' surfaces)\\n\\n';

      report += '## 💭 Group-Level Directives & Goals\\n';
      if (groupComment) {
        report += groupComment + '\\n\\n';
      } else {
        report += '*(No group-wide instructions provided)*\\n\\n';
      }

      report += '## 📦 Included Surfaces in Group\\n';
      const cardsInDomain = domainItems.filter(i => i.kind === 'card');
      const popupsInDomain = domainItems.filter(i => i.kind === 'popup');
      const widgetsInDomain = domainItems.filter(i => i.kind === 'widget');
      if (cardsInDomain.length) report += '- **Launcher Cards**: ' + cardsInDomain.map(c => c.title).join(', ') + '\\n';
      if (popupsInDomain.length) report += '- **Popups**: ' + popupsInDomain.map(p => p.title).join(', ') + '\\n';
      if (widgetsInDomain.length) report += '- **Widgets**: ' + widgetsInDomain.map(w => w.badge).join(', ') + '\\n';
      report += '\\n';

      if (itemFeedbackList.length > 0) {
        report += '## 📝 Specific Surface Adjustments (' + itemFeedbackList.length + ' reviewed)\\n\\n';
        itemFeedbackList.forEach(item => {
          const fb = userFeedback[item.uid];
          report += '### [' + (fb.status || 'REVIEW').toUpperCase() + '] ' + item.title + ' (' + (item.size || item.kind) + ')\\n';
          if (fb.comment && fb.comment.trim()) {
            report += '- **Instruction**: ' + fb.comment.trim() + '\\n';
          }
          if (item.overflowCount > 0) {
            report += '- **Audit Warning**: ' + item.overflowCount + ' layout overflow warnings detected.\\n';
          }
          report += '\\n';
        });
      } else {
        report += '## 📝 Specific Surface Adjustments\\n*(No individual surface overrides set yet)*\\n\\n';
      }

      if (overflowItems.length > 0) {
        report += '## ⚠️ Automated Layout Overflows in this Group\\n';
        overflowItems.forEach(i => {
          report += '- **' + i.title + '** (' + i.kind + ' · ' + i.dim + '): ' + i.overflowCount + ' overflow warnings.\\n';
        });
        report += '\\n';
      }

      navigator.clipboard.writeText(report);
      showToast('Copied ' + d.label + ' group report to clipboard!');
    }

    function copyHeaderReport() {
      if (currentDomain !== 'all') {
        copyGroupReport(currentDomain);
      } else {
        copyAllFeedbackReport();
      }
    }

    function copyAllFeedbackReport() {
      let report = '# Haval H6 3D — Complete Visual Review & Feedback Report\\n\\n';
      
      DOMAINS.forEach(d => {
        const domainItems = allItems.filter(i => i.domain === d.id);
        const groupComment = (groupFeedback[d.id] || '').trim();
        const itemFeedbackList = domainItems.filter(item => {
          const fb = userFeedback[item.uid];
          return fb && ((fb.comment && fb.comment.trim()) || (fb.status && fb.status !== 'pending'));
        });

        if (!groupComment && itemFeedbackList.length === 0) return;

        report += '## ' + d.icon + ' ' + d.label + '\\n';
        if (groupComment) {
          report += '**Group Directives**: ' + groupComment + '\\n\\n';
        }

        itemFeedbackList.forEach(item => {
          const fb = userFeedback[item.uid];
          report += '- **[' + (fb.status || 'REVIEW').toUpperCase() + '] ' + item.title + '**: ' + (fb.comment ? fb.comment.trim() : 'Status updated') + '\\n';
        });
        report += '\\n';
      });

      navigator.clipboard.writeText(report);
      showToast('Copied full feedback report across all groups to clipboard!');
    }

    function exportFeedbackJson() {
      const exportData = {
        exportedAt: new Date().toISOString(),
        groupFeedback,
        surfaceFeedback: userFeedback
      };
      const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'h6_visual_feedback.json';
      a.click();
    }

    renderView();
  </script>
</body>
</html>`;

fs.writeFileSync(path.join(SNAP_DIR, 'index.html'), htmlContent);
console.log('Successfully generated upgraded Visual Review & Feedback Studio in snapshots/index.html');
