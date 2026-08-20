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
    var html = document.documentElement;
    function setVar(name, value) {
      var px = value + 'px';
      if (el) el.style.setProperty(name, px);
      // Splash SKIP sits outside #hv-root and inherits from <html>.
      if (html) html.style.setProperty(name, px);
    }
    if (typeof layout.safeTop === 'number') setVar('--hv-safe-top', layout.safeTop);
    if (typeof layout.safeLeft === 'number') setVar('--hv-safe-left', layout.safeLeft);
    if (typeof layout.safeRight === 'number') setVar('--hv-safe-right', layout.safeRight);
    if (typeof layout.safeBottom === 'number') setVar('--hv-safe-bottom', layout.safeBottom);
    if (typeof layout.launcherBottom === 'number') setVar('--hv-launcher-bottom', layout.launcherBottom);
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
