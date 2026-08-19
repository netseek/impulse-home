(function() {
  'use strict';
  // Native Android launcher UI lives in MainActivity.java
  // (icon strip + left freeform popups + right media slot).
  //
  // Shell layout:
  //   window.onAndroidShellLayout({
  //     left, right: 'idle'|'app',
  //     mode: 'triple'|'appCar'|'appsOnly',
  //     launchSide: 'auto'|'left'|'right', nextSide: 'left'|'right',
  //     leftBounds, rightBounds,
  //     safeTop, safeLeft, safeRight, safeBottom, launcherBottom
  //   })
  //   - left  → left freeform app is open
  //   - right → 'idle' now-playing card, or 'app' in the right slot
  //   - mode  → triple (app+car+app), appCar, appsOnly (session-only)
  // Native: AppLauncherBridge.setShellMode(mode), setLaunchSide(side),
  //         dismissOverlays() — closes freeform so WebView menus can show
  //         setSlotUse(side, 'app'|'widgets') — implicit: adding a widget
  //         claims the slot; launching an app claims it; emptying it frees it.
  //         saveWidgets(json), loadWidgets()
  // Fallback: window.onAndroidLauncherPopup(active: boolean)  (left only)
  //
  // Now playing:
  //   window.onMediaNowPlaying({ title, artist, album, durationMs, positionMs,
  //     playing, appLabel, packageName, artDataUrl, hasTrack })
  //   window.onMediaPosition(positionMs)
  // Transport: window.MediaBridge.prev|playPause|next()

  var pendingShell = null;
  var pendingMedia = null;

  // The safe-area insets have to land even before the viewer registers
  // window.__app: native sends them while the GLB is still loading, and without
  // this the whole load renders with no side band and the chrome under the
  // MMI header. The root element exists as soon as the page paints.
  function applySafeInsets(layout) {
    var el = document.getElementById('hv-root');
    if (!el) return;
    if (typeof layout.safeTop === 'number') el.style.setProperty('--hv-safe-top', layout.safeTop + 'px');
    if (typeof layout.safeLeft === 'number') el.style.setProperty('--hv-safe-left', layout.safeLeft + 'px');
    if (typeof layout.safeRight === 'number') el.style.setProperty('--hv-safe-right', layout.safeRight + 'px');
    if (typeof layout.safeBottom === 'number') el.style.setProperty('--hv-safe-bottom', layout.safeBottom + 'px');
    if (typeof layout.launcherBottom === 'number') el.style.setProperty('--hv-launcher-bottom', layout.launcherBottom + 'px');
  }

  function applyShell(layout) {
    if (!layout || typeof layout !== 'object') return;
    window.__androidShell = layout;
    window.__androidPopupActive = !!layout.left;
    applySafeInsets(layout);
    if (window.__app && typeof window.__app.applyShellLayout === 'function') {
      window.__app.applyShellLayout(layout);
    } else if (window.__app && typeof window.__app.applyLauncherPopupLayout === 'function') {
      window.__app.applyLauncherPopupLayout(!!layout.left);
    }
  }

  window.onAndroidShellLayout = function(layout) {
    pendingShell = layout;
    applyShell(layout);
  };

  window.onAndroidLauncherPopup = function(active) {
    var right = (pendingShell && pendingShell.right) || 'idle';
    window.onAndroidShellLayout({ left: !!active, right: right });
  };

  window.onMediaNowPlaying = function(payload) {
    pendingMedia = payload || {};
    if (window.__app && typeof window.__app.applyMediaNowPlaying === 'function') {
      window.__app.applyMediaNowPlaying(pendingMedia);
    }
  };

  window.onMediaPosition = function(ms) {
    if (window.__app && typeof window.__app.applyMediaPosition === 'function') {
      window.__app.applyMediaPosition(ms);
    }
  };

  window.__flushAndroidShell = function() {
    if (pendingShell) applyShell(pendingShell);
    // Media is applied by the viewer after loading finishes (see applyMediaNowPlaying).
    if (pendingMedia && window.__app && typeof window.__app.applyMediaNowPlaying === 'function') {
      window.__app.applyMediaNowPlaying(pendingMedia);
    }
  };
})();
