(function() {
  'use strict';
  // Native Android launcher UI lives in MainActivity.java (icon strip + freeform popups).
  //
  // Contract with native (MainActivity.notifyViewerPopupLayout):
  //   window.onAndroidLauncherPopup(active: boolean)
  //   - true  → left freeform app is open; viewer reframes car slightly right + zoomed out
  //   - false → popup closed/maximized; restore default fit framing
  // Fallback: window.__app.applyLauncherPopupLayout(active)
  //
  // Native also launches apps into LEFT_POPUP_BOUNDS (see MainActivity).
})();
