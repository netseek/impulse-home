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
  //     leftBounds, rightBounds
  //   })
  //   - left  → left freeform app is open
  //   - right → 'idle' now-playing card, or 'app' in the right slot
  //   - mode  → triple (app+car+app), appCar, appsOnly (session-only)
  // Native: AppLauncherBridge.setShellMode(mode), setLaunchSide(side),
  //         dismissOverlays() — closes freeform so WebView menus can show
  //         setSlotUse(side, 'app'|'widgets'), saveWidgets(json), loadWidgets()
  // Fallback: window.onAndroidLauncherPopup(active: boolean)  (left only)
  //
  // Now playing:
  //   window.onMediaNowPlaying({ title, artist, album, durationMs, positionMs,
  //     playing, appLabel, packageName, artDataUrl, hasTrack })
  //   window.onMediaPosition(positionMs)
  // Transport: window.MediaBridge.prev|playPause|next()

  var pendingShell = null;
  var pendingMedia = null;

  function applyShell(layout) {
    if (!layout || typeof layout !== 'object') return;
    window.__androidShell = layout;
    window.__androidPopupActive = !!layout.left;
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
