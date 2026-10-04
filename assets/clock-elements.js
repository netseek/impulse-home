(function (root) {
  'use strict';

  var Core = root.H6ClockCore;
  if (!Core || !root.customElements || !root.HTMLElement) return;

  var NS = 'http://www.w3.org/2000/svg';

  function dialMarkup() {
    var ticks = '';
    for (var i = 0; i < 12; i++) {
      var cardinal = i % 3 === 0;
      ticks += '<line class="tick ' + (cardinal ? 'cardinal' : 'minor')
        + '" x1="50" y1="' + (cardinal ? '37' : '40') + '" x2="50" y2="6" transform="rotate('
        + (i * 30) + ' 50 50)" />';
    }
    return '<svg class="dial" viewBox="0 0 100 100" aria-hidden="true" focusable="false">'
      + '<g class="ticks">' + ticks + '</g>'
      + '<g class="numerals"><text x="50" y="22">12</text><text x="79" y="55">3</text><text x="50" y="86">6</text><text x="21" y="55">9</text></g>'
      + '<g class="hour-hand"><line x1="50" y1="50" x2="50" y2="25" /></g>'
      + '<g class="minute-hand"><line class="under" x1="50" y1="50" x2="50" y2="15" /><line class="accent-tip" x1="50" y1="21" x2="50" y2="15" /></g>'
      + '<circle class="hub-outline" cx="50" cy="50" r="3.2"/><circle class="hub" cx="50" cy="50" r="2.4"/>'
      + '</svg>';
  }

  var STYLE = [
    ':host{display:block;width:100%;height:100%;min-width:0;min-height:0;color:var(--hv-widget-fg,#eaf2f8);font-family:var(--hv-font),"DM Sans",system-ui,sans-serif;contain:layout style paint;}',
    '*{box-sizing:border-box}.widget,.rail{width:100%;height:100%;overflow:hidden}.rail{display:none}',
    ':host([surface="rail"]) .widget{display:none}:host([surface="rail"]) .rail{display:block}',
    '.widget{--time:72px;--meta:20px;--dial:160px;display:flex;align-items:center;justify-content:center;padding:16px;gap:16px;font-variant-numeric:tabular-nums;}',
    '.copy{min-width:0;display:flex;flex-direction:column;justify-content:center;align-items:flex-start;flex:1}.time-row{display:flex;align-items:baseline;gap:7px;min-width:0;white-space:nowrap}',
    '.time{font-size:var(--time);font-weight:500;line-height:1.05;letter-spacing:-.025em}.period{font-size:var(--meta);font-weight:600;line-height:1.1;color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce));}',
    '.date{max-width:100%;margin-top:8px;font-size:var(--meta);font-weight:500;line-height:1.25;color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce));overflow:hidden;text-overflow:ellipsis;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}',
    '.dial{display:block;width:var(--dial);height:var(--dial);flex:0 0 var(--dial);overflow:visible}.tick{stroke:currentColor;stroke-linecap:round}.tick.minor{stroke-width:1.6;opacity:.52}.tick.cardinal{stroke-width:2.1}.tick.cardinal:first-child{stroke:var(--hv-accent,#2aa7b7)}',
    '.hour-hand line,.minute-hand .under{stroke:currentColor;stroke-linecap:round}.hour-hand line{stroke-width:3.6}.minute-hand .under{stroke-width:2.2}.minute-hand .accent-tip{display:none;stroke:var(--hv-accent,#2aa7b7);stroke-width:2.4;stroke-linecap:round}.hub-outline{fill:var(--hv-widget-fg,#eaf2f8)}.hub{fill:var(--hv-accent,#2aa7b7)}',
    '.numerals{display:none;fill:currentColor;font:600 10px system-ui,sans-serif;text-anchor:middle;dominant-baseline:middle}',
    '.signal-line{display:none;width:42px;height:2px;margin-top:22px;background:var(--hv-accent,#2aa7b7)}',
    '.date-tile{display:none;flex:0 0 auto;width:88px;height:96px;border:1px solid var(--hv-frost-edge,rgba(255,255,255,.18));border-radius:14px;background:var(--hv-frost-inner,rgba(255,255,255,.05));align-items:center;justify-content:center;flex-direction:column;color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce))}.date-tile strong{font-size:36px;line-height:1;font-weight:500;color:var(--hv-widget-fg,#eaf2f8)}.date-tile span{margin-top:6px;font-size:16px;font-weight:600;text-transform:uppercase}',
    '.datebook-month{display:none;position:absolute;left:16px;bottom:78px;font-size:var(--meta);color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce))}.week{display:none;position:absolute;left:16px;right:16px;bottom:16px;height:52px;grid-template-columns:repeat(7,minmax(48px,1fr));gap:2px}.week-cell{display:flex;align-items:center;justify-content:center;flex-direction:column;border:1px solid transparent;border-radius:9px;color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce));font-size:13px;line-height:1.05}.week-cell strong{margin-top:4px;color:var(--hv-widget-fg,#eaf2f8);font-size:17px;font-weight:500}.week-cell.today{border-color:currentColor;box-shadow:inset 0 -3px 0 var(--hv-accent,#2aa7b7)}',
    ':host([size="1x1"]) .widget{--time:40px;--meta:16px;--dial:56px;padding:10px;gap:8px;flex-direction:column;text-align:center}:host([size="1x1"]) .copy{align-items:center;flex:0 0 auto}:host([size="1x1"]) .date{margin-top:3px}',
    ':host([size="2x1"]) .widget{--time:64px;--meta:18px;--dial:112px}:host([size="1x2"]) .widget{--time:52px;--meta:18px;--dial:120px;flex-direction:column;text-align:center}:host([size="1x2"]) .copy{align-items:center;flex:0 0 auto}:host([size="2x2"]) .widget{--time:72px;--meta:20px;--dial:160px}',
    ':host([clock-style="atelier"]) .widget{--dial:192px;flex-direction:column;gap:8px}:host([clock-style="atelier"]) .copy{align-items:center;flex:0 0 auto;text-align:center}:host([clock-style="atelier"]) .minute-hand .accent-tip{display:block}',
    ':host([clock-style="atelier"][size="1x1"]) .widget{--time:36px;--dial:56px}:host([clock-style="atelier"][size="2x1"]) .widget{--time:48px;--dial:128px;flex-direction:row;gap:16px}:host([clock-style="atelier"][size="1x2"]) .widget{--time:40px;--dial:140px}:host([clock-style="atelier"][size="2x2"]) .widget{--time:52px;--dial:192px}',
    ':host([clock-style="atelier"][show-numerals]) .numerals{display:block}:host([clock-style="atelier"][show-numerals]) .tick.cardinal{opacity:.36}',
    ':host([clock-style="signal"]) .widget{--time:96px;justify-content:flex-start}:host([clock-style="signal"]) .dial{display:none}:host([clock-style="signal"]) .signal-line{display:block}:host([clock-style="signal"][size="1x1"]) .widget{--time:44px;align-items:flex-start;text-align:left}:host([clock-style="signal"][size="1x1"]) .copy{align-items:flex-start}:host([clock-style="signal"][size="1x2"]) .widget{--time:56px;align-items:flex-start;text-align:left}:host([clock-style="signal"][size="1x2"]) .copy{align-items:flex-start}:host([clock-style="signal"][size="2x1"]) .widget{--time:72px}:host([clock-style="signal"][size="2x2"]) .widget{--time:96px}',
    ':host([clock-style="datebook"]) .widget{--time:72px;position:relative;justify-content:flex-start}:host([clock-style="datebook"]) .dial{display:none}:host([clock-style="datebook"][show-tile]) .date-tile{display:flex}:host([clock-style="datebook"][show-week]) .week{display:grid}:host([clock-style="datebook"][show-week]) .datebook-month{display:block}:host([clock-style="datebook"][show-week]) .widget{padding-bottom:84px}',
    ':host([clock-style="datebook"][size="1x1"]) .widget{--time:44px;align-items:flex-start;text-align:left}:host([clock-style="datebook"][size="1x1"]) .copy{align-items:flex-start}:host([clock-style="datebook"][size="1x2"]) .widget{--time:52px;align-items:flex-start;text-align:left}:host([clock-style="datebook"][size="1x2"]) .copy{align-items:flex-start}:host([clock-style="datebook"][size="2x1"]) .widget{--time:64px}:host([clock-style="datebook"][size="2x2"]) .widget{--time:72px}',
    ':host([compact]) .widget{--time:36px;--meta:16px;--dial:56px;padding:10px;gap:8px;flex-direction:column;text-align:center}:host([compact]) .copy{align-items:center;flex:0 0 auto}:host([compact][short]) .date{display:none}:host([compact][digital-fallback]) .dial{display:none}',
    ':host([wide-short]) .widget{--time:44px;--meta:16px;--dial:72px;flex-direction:row;padding:10px 16px;gap:16px;text-align:left}:host([wide-short]) .copy{align-items:flex-start;flex:1}:host([wide-short]) .date-tile,:host([wide-short]) .week{display:none}',
    '.rail{position:relative;font-variant-numeric:tabular-nums}.rail-face{display:none;position:absolute;inset:0;padding:8px;color:var(--hv-widget-fg,#eaf2f8)}',
    ':host([rail-face="panorama"]) .panorama,:host([rail-face="meridian"]) .meridian,:host([rail-face="split"]) .split,:host([rail-face="date-spine"]) .date-spine{display:block}',
    '.rail-time{font-weight:500;letter-spacing:-.025em;line-height:.84;white-space:nowrap}.rail-period{position:absolute;font-size:16px;font-weight:600;color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce))}',
    '.panorama .rail-time{position:absolute;left:8px;right:8px;top:2px;font-size:84px}.panorama .rail-period{right:8px;top:51px}.panorama .dial{position:absolute;left:8px;bottom:4px;width:48px;height:48px}.panorama .rail-date{position:absolute;left:68px;right:8px;bottom:7px;font-size:16px;line-height:1.18;color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce))}.panorama .rail-date span{display:block}',
    '.meridian .dial{position:absolute;left:6px;top:8px;width:108px;height:108px}.meridian .stack{position:absolute;left:130px;right:8px;top:8px;bottom:8px;display:flex;flex-direction:column;justify-content:center;align-items:center}.meridian .stack::after{content:"";position:absolute;left:0;right:0;top:50%;height:1px;background:var(--hv-frost-edge,rgba(255,255,255,.18))}.meridian .stack strong{position:relative;z-index:1;font-size:48px;font-weight:500;line-height:1}.meridian .colon{position:absolute;z-index:2;left:50%;top:50%;transform:translate(-50%,-50%);padding:0 5px;background:var(--hv-frost-fill-strong,#0c1219);font-size:24px;color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce))}.meridian .rail-period{right:7px;bottom:2px}',
    '.split{display:none!important}.split .plates{position:absolute;left:8px;right:8px;top:8px;height:92px;display:grid;grid-template-columns:1fr 18px 1fr;align-items:center}.split .plate{height:92px;display:flex;align-items:center;justify-content:center;border:1px solid var(--hv-frost-edge,rgba(255,255,255,.18));border-radius:15px;background:var(--hv-frost-inner,rgba(255,255,255,.06));font-size:70px;font-weight:500;line-height:1}.split.flat .plate{background:transparent;box-shadow:none}.split .colon{font-size:34px;text-align:center}.split .rail-date{position:absolute;left:8px;right:8px;bottom:2px;text-align:center;font-size:16px;line-height:1.1;color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce))}.split .rail-period{left:8px;bottom:1px}',
    ':host([rail-face="split"]) .split{display:block!important}.date-spine{padding:0 8px 0 66px}.date-band{position:absolute;left:0;top:0;bottom:0;width:58px;display:flex;align-items:center;justify-content:center;flex-direction:column;background:var(--hv-frost-inner,rgba(255,255,255,.07));color:var(--clock-secondary,var(--hv-widget-muted,#b6c2ce));text-transform:uppercase}.date-band span{font-size:16px;line-height:1}.date-band strong{margin:10px 0;font-size:32px;line-height:1;font-weight:500;color:var(--hv-widget-fg,#eaf2f8)}.date-band::after{content:"";position:absolute;left:23px;right:23px;bottom:21px;height:3px;background:var(--hv-accent,#2aa7b7)}.date-spine .rail-time{position:absolute;left:66px;right:8px;top:36px;font-size:60px}.date-spine .rail-period{left:66px;bottom:7px}',
    ':host([hour-cycle="12"]) .panorama .rail-time{font-size:68px;right:38px}:host([hour-cycle="12"]) .meridian .stack strong{font-size:42px}:host([hour-cycle="12"]) .split .plate{font-size:58px}:host([hour-cycle="12"]) .date-spine .rail-time{font-size:52px}',
    '@media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}'
  ].join('');

  function setText(element, selector, value, runtime) {
    var node = element.shadowRoot.querySelector(selector);
    value = value == null ? '' : String(value);
    if (node && node.textContent !== value) {
      node.textContent = value;
      runtime.counters.changedNodes++;
    }
  }

  function setTransform(element, selector, angle, runtime) {
    var node = element.shadowRoot.querySelector(selector);
    var value = 'rotate(' + angle + ' 50 50)';
    if (node && node.getAttribute('transform') !== value) {
      node.setAttribute('transform', value);
      runtime.counters.changedNodes++;
    }
  }

  function environmentFromBridge() {
    var locale = (root.navigator && root.navigator.language) || 'en-US';
    var timeZone = '';
    try { timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) {}
    var env = { locale: locale, timeZone: timeZone, system24: Core.systemUses24Hour(locale) };
    try {
      if (root.AppLauncherBridge && typeof root.AppLauncherBridge.getClockEnvironment === 'function') {
        var nativeEnv = JSON.parse(root.AppLauncherBridge.getClockEnvironment() || '{}');
        if (typeof nativeEnv.locale === 'string' && nativeEnv.locale) env.locale = nativeEnv.locale;
        if (typeof nativeEnv.timeZone === 'string') env.timeZone = nativeEnv.timeZone;
        if (typeof nativeEnv.system24 === 'boolean') env.system24 = nativeEnv.system24;
      }
    } catch (e) {}
    return env;
  }

  // One formatter per time zone. Building an Intl.DateTimeFormat is slow on the
  // head unit, and this runs on every dock sync: measured ~19% of a
  // _syncDockIndicators call on the car (2026-09-25) with a new one each time.
  var hour24Formatters = {};
  function hour24At(now, environment) {
    try {
      var zone = environment.timeZone || '';
      var fmt = hour24Formatters[zone] || (hour24Formatters[zone] = new Intl.DateTimeFormat('en-GB', {
        hour: '2-digit', hourCycle: 'h23', timeZone: zone || undefined
      }));
      var local = fmt.format(now);
      return parseInt(local, 10) % 24;
    } catch (e) {
      return now.getHours();
    }
  }

  function faceAppearance(element) {
    var accent = '#2aa7b7';
    try {
      var style = root.getComputedStyle && root.getComputedStyle(element);
      accent = (style && style.getPropertyValue('--hv-accent').trim()) || accent;
    } catch (e) {}
    var light = false;
    try {
      light = !!(root.__app && root.__app._effectiveWidgetTheme
        && root.__app._effectiveWidgetTheme(root.__app.state) === 'light');
    } catch (e) {}
    return { accent: accent, light: light };
  }

  var Runtime = {
    elements: new Set(),
    timer: 0,
    environment: environmentFromBridge(),
    native: { config: null, appearance: null, timer: 0, key: '', pendingKey: '', sequence: 0,
      revision: String(Date.now()) + '-' + Math.random().toString(36).slice(2) },
    counters: { ticks: 0, refreshes: 0, elementUpdates: 0, changedNodes: 0, timerStarts: 0, timerCancels: 0, visibilityRefreshes: 0, parentStateViolations: 0 },
    register: function (element) {
      this.elements.add(element);
      this.refreshElement(element, new Date());
      this.syncVisibility();
    },
    unregister: function (element) {
      this.elements.delete(element);
      this.syncVisibility();
    },
    visible: function (element) {
      if (!element || !element.isConnected || document.visibilityState === 'hidden') return false;
      if (element.closest && element.closest('.hv-desktop-page[data-desktop-active="false"]')) return false;
      if (element.closest && element.closest('.hv-desktop-studio:not(.on), .hv-desks:not(.on), .hv-wallpaper-pop:not(.on), .hv-card-focus:not(.on)')) return false;
      var rects = element.getClientRects();
      if (!rects || !rects.length) return false;
      var style = root.getComputedStyle ? root.getComputedStyle(element) : null;
      return !style || (style.display !== 'none' && style.visibility !== 'hidden');
    },
    visibleElements: function () {
      var self = this;
      return Array.from(this.elements).filter(function (element) { return self.visible(element); });
    },
    needsSecondTick: function () {
      if (this.native.config && this.native.config.face === 'panorama') return true;
      var visible = this.visibleElements();
      for (var i = 0; i < visible.length; i++) {
        var face = visible[i].getAttribute('rail-face') || visible[i].getAttribute('clock-style');
        if (face === 'panorama' || face === 'signal') return true;
      }
      return false;
    },
    refreshElement: function (element, now) {
      if (!element || !element.isConnected) return;
      element.refresh(now || new Date(), this.environment, this);
    },
    refreshVisible: function (reason) {
      var now = new Date();
      var before = root.__app && root.__app._setStateCount ? root.__app._setStateCount : 0;
      var visible = this.visibleElements();
      for (var i = 0; i < visible.length; i++) this.refreshElement(visible[i], now);
      var after = root.__app && root.__app._setStateCount ? root.__app._setStateCount : 0;
      if (after !== before) this.counters.parentStateViolations += after - before;
      this.counters.refreshes++;
      if (reason === 'timer') this.counters.ticks++;
    },
    setNativeCardConfig: function (config, appearance) {
      if (!config || !root.H6ClockFaces) return;
      var next = {
        face: config.face || 'panorama', hourFormat: config.hourFormat || 'system',
        dialMarks: config.dialMarks || 'index'
      };
      // Called from every _syncDockIndicators, i.e. on car signals, not only on
      // clock changes: measured ~40% of a dock sync was this re-publishing an
      // unchanged face. The native timer already handles the ticks.
      var sig = JSON.stringify([next, appearance || {}]);
      if (sig === this.native.configSig && this.native.timer) return;
      this.native.configSig = sig;
      this.native.config = next;
      this.native.appearance = appearance || {};
      this.publishNativeCard(new Date());
      this.scheduleNative();
    },
    publishNativeCard: function (now) {
      var native = this.native;
      if (!native.config || !root.H6ClockFaces || !root.AppLauncherBridge
          || typeof root.AppLauncherBridge.updateClockCardFace !== 'function') return;
      var snap = Core.snapshot(now, { hourFormat: native.config.hourFormat }, this.environment);
      var faceOptions = {
        hour24: hour24At(now, this.environment), dialMarks: native.config.dialMarks,
        accent: native.appearance && native.appearance.accent,
        light: !!(native.appearance && native.appearance.light)
      };
      // Native strokes the panorama seconds sweep itself when the APK can, so
      // the face is rasterized once a minute instead of once a second: the PNG
      // encode below cost the main thread 25-160 ms EVERY second on the car.
      var sweep = this.nativeSweep() && root.H6ClockFaces.sweep
        ? root.H6ClockFaces.sweep(native.config.face, faceOptions) : null;
      var sweepJson = sweep ? JSON.stringify(sweep) : '';
      if (sweepJson !== native.sweepJson) {
        native.sweepJson = sweepJson;
        try { root.AppLauncherBridge.setClockCardSweep(native.revision, sweepJson); } catch (e) {}
      }
      faceOptions.omitSweep = !!sweep;
      var svg = root.H6ClockFaces.render(native.config.face, snap, faceOptions);
      var key = native.config.face + '|' + native.config.hourFormat + '|' + native.config.dialMarks
        + '|' + (native.appearance && native.appearance.accent || '') + '|'
        + (native.appearance && native.appearance.light ? '1' : '0') + '|' + snap.dateKey + '|' + snap.time
        + '|' + (native.config.face === 'panorama' && !sweep ? snap.second : '');
      if (key === native.key || key === native.pendingKey) return;
      native.pendingKey = key;
      // A face change landing mid-switch (each desktop has its own clock face)
      // used to rasterize and PNG-encode inside the slide: 631 ms in one task on
      // the car. Hold it until the slide settles; the newest request wins.
      var body = root.document && root.document.body;
      if (body && body.classList && (body.classList.contains('hv-desk-sliding')
          || body.classList.contains('hv-desk-animating'))) {
        native.pendingKey = '';
        root.clearTimeout(native.holdTimer);
        native.holdTimer = root.setTimeout(function () { Runtime.publishNativeCard(new Date()); }, 250);
        return;
      }
      var sequence = ++native.sequence;
      var image = new Image();
      var url = '';
      var self = this;
      image.onload = function () {
        if (sequence !== native.sequence) { if (url) root.URL.revokeObjectURL(url); return; }
        try {
          var canvas = document.createElement('canvas');
          canvas.width = 448; canvas.height = 248;
          var context = canvas.getContext('2d');
          context.drawImage(image, 0, 0, canvas.width, canvas.height);
          // toBlob encodes off the main thread; toDataURL did it inline, 25-160 ms
          // on a quiet car and far worse mid-switch.
          var send = function (dataUrl) {
            if (sequence === native.sequence) {
              try {
                root.AppLauncherBridge.updateClockCardFace(native.revision, sequence, dataUrl);
                native.key = key;
              } catch (e) {}
            }
            native.pendingKey = '';
          };
          if (canvas.toBlob && root.FileReader) {
            canvas.toBlob(function (blob) {
              if (!blob) { native.pendingKey = ''; return; }
              var reader = new root.FileReader();
              reader.onload = function () { send(String(reader.result)); };
              reader.onerror = function () { native.pendingKey = ''; };
              reader.readAsDataURL(blob);
            }, 'image/png');
          } else {
            send(canvas.toDataURL('image/png'));
          }
          if (url) root.URL.revokeObjectURL(url);
          return;
        } catch (e) {}
        native.pendingKey = '';
        if (url) root.URL.revokeObjectURL(url);
      };
      image.onerror = function () { native.pendingKey = ''; if (url) root.URL.revokeObjectURL(url); };
      try {
        url = root.URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
        image.src = url;
      } catch (e) { native.pendingKey = ''; }
    },
    nativeSweep: function () {
      return !!(root.AppLauncherBridge && typeof root.AppLauncherBridge.setClockCardSweep === 'function');
    },
    cancelNative: function () {
      if (!this.native.timer) return;
      root.clearTimeout(this.native.timer);
      this.native.timer = 0;
    },
    scheduleNative: function () {
      var self = this;
      this.cancelNative();
      if (!this.native.config) return;
      this.native.timer = root.setTimeout(function () {
        self.native.timer = 0;
        self.publishNativeCard(new Date());
        self.scheduleNative();
      }, this.native.config.face === 'panorama' && !this.nativeSweep()
        ? (1000 - (Date.now() % 1000) + 20) : Core.nextMinuteDelay(Date.now()));
    },
    refreshNative: function () {
      this.publishNativeCard(new Date());
      this.scheduleNative();
    },
    cancel: function () {
      if (!this.timer) return;
      root.clearTimeout(this.timer);
      this.timer = 0;
      this.counters.timerCancels++;
    },
    schedule: function () {
      var self = this;
      this.cancel();
      if (!this.visibleElements().length) return;
      this.timer = root.setTimeout(function () {
        self.timer = 0;
        self.refreshVisible('timer');
        self.schedule();
      }, this.needsSecondTick() ? (1000 - (Date.now() % 1000) + 20) : Core.nextMinuteDelay(Date.now()));
      this.counters.timerStarts++;
    },
    syncVisibility: function () {
      this.counters.visibilityRefreshes++;
      if (this.visibleElements().length) this.schedule();
      else this.cancel();
    },
    environmentChanged: function () {
      this.environment = environmentFromBridge();
      Core.clearFormatterCache();
      this.refreshVisible('environment');
      this.refreshNative();
      this.schedule();
    },
    inspect: function () {
      var copy = {};
      Object.keys(this.counters).forEach(function (key) { copy[key] = Runtime.counters[key]; });
      copy.subscribers = this.elements.size;
      copy.visibleSubscribers = this.visibleElements().length;
      copy.activeTimers = this.timer ? 1 : 0;
      copy.nativeClockTimer = this.native.timer ? 1 : 0;
      copy.parentSetState = copy.parentStateViolations;
      copy.sceneRenderRequests = 0;
      copy.postFxInvalidations = 0;
      copy.nextDelayMs = Core.nextMinuteDelay(Date.now());
      return copy;
    },
    destroy: function () {
      this.cancel();
      this.cancelNative();
      this.elements.clear();
    }
  };

  function H6ClockElement() {
    var self = Reflect.construct(HTMLElement, [], H6ClockElement);
    self._built = false;
    self._lastDateKey = '';
    self._resizeObserver = null;
    return self;
  }
  H6ClockElement.prototype = Object.create(HTMLElement.prototype);
  H6ClockElement.prototype.constructor = H6ClockElement;
  Object.setPrototypeOf(H6ClockElement, HTMLElement);
  Object.defineProperty(H6ClockElement, 'observedAttributes', { get: function () {
    return ['clock-style', 'rail-face', 'hour-format', 'dial-marks', 'split-plates', 'spine-format', 'date-wording', 'size', 'surface'];
  }});

  H6ClockElement.prototype.connectedCallback = function () {
    if (!this._built) this._build();
    var self = this;
    if (root.ResizeObserver && !this._resizeObserver) {
      this._resizeObserver = new ResizeObserver(function () { self._fit(); Runtime.syncVisibility(); });
      this._resizeObserver.observe(this);
    }
    this._fit();
    Runtime.register(this);
  };

  H6ClockElement.prototype.disconnectedCallback = function () {
    Runtime.unregister(this);
    if (this._resizeObserver) this._resizeObserver.disconnect();
    this._resizeObserver = null;
  };

  H6ClockElement.prototype.attributeChangedCallback = function () {
    if (!this._built || !this.isConnected) return;
    this._fit();
    Runtime.refreshElement(this, new Date());
    Runtime.syncVisibility();
  };

  H6ClockElement.prototype._build = function () {
    var shadow = this.attachShadow({ mode: 'open' });
    shadow.innerHTML = '<style>'
      + ':host{display:block;width:100%;height:100%;min-width:0;min-height:0;contain:layout style paint;font-variant-numeric:tabular-nums;}'
      + '*{box-sizing:border-box}.clock-stage{width:100%;height:100%;overflow:hidden;display:flex;align-items:center;justify-content:center;}'
      + '.clock-stage>svg{display:block;width:100%;height:100%;}'
      + '</style><div class="clock-stage"></div>';
    this._built = true;
  };

  H6ClockElement.prototype._fit = function () {
    var w = this.clientWidth || 0, h = this.clientHeight || 0;
    var surface = this.getAttribute('surface') || 'widget';
    var compact = surface !== 'rail' && (w < 280 || h < 160);
    var wideShort = surface !== 'rail' && w >= 280 && h < 160;
    this.toggleAttribute('compact', compact);
    this.toggleAttribute('wide-short', wideShort);
    this.toggleAttribute('short', h < 160);
    this.toggleAttribute('digital-fallback', w > 0 && h > 0 && (w < 144 || h < 132));
  };

  function mapClockStyle(style) {
    var val = String(style == null ? '' : style).toLowerCase();
    if (val === 'twin' || val === 'atelier') return 'meridian';
    if (val === 'signal') return 'panorama';
    if (val === 'datebook') return 'date-spine';
    return (root.H6ClockFaces && root.H6ClockFaces.names[val]) ? val : 'meridian';
  }

  H6ClockElement.prototype.refresh = function (now, environment, runtime) {
    var requested = this.getAttribute('hour-format') || 'system';
    var snap;
    try { snap = Core.snapshot(now, { hourFormat: requested }, environment); }
    catch (e) { snap = { valid: false, time: '—:—', hour: '—', minute: '—', dayPeriod: '', dateNumeric: '', dateMedium: 'Time unavailable', dateFull: 'Time unavailable', weekdayShort: '', weekdayLong: '', monthShort: '', monthLong: '', monthYear: '', day: '', dateKey: '', minuteAngle: 0, hourAngle: 0, accessible: 'Time unavailable' }; }
    var resolved = snap.resolvedFormat || Core.resolvedHourFormat(requested, environment.system24);
    this.setAttribute('hour-cycle', resolved);
    this.setAttribute('role', 'group');
    this.setAttribute('aria-label', snap.accessible + '. Customize clock');

    if (root.H6ClockFaces) {
      var host = this.shadowRoot.querySelector('.clock-stage') || this.shadowRoot;
      var rawFace = this.getAttribute('surface') === 'rail' ? this.getAttribute('rail-face') : this.getAttribute('clock-style');
      var face = mapClockStyle(rawFace || this.getAttribute('rail-face') || this.getAttribute('clock-style'));
      var size = this.getAttribute('surface') === 'rail' ? 'rail' : (this.getAttribute('size') || '2x1');
      var appearance = faceAppearance(this);
      var svg = root.H6ClockFaces.render(face, snap, {
        size: size,
        hour24: hour24At(now, environment),
        dialMarks: this.getAttribute('dial-marks'),
        accent: appearance.accent,
        light: appearance.light
      });
      if (host && this._faceSvg !== svg) {
        host.innerHTML = svg;
        if (host.firstElementChild) {
          host.firstElementChild.style.cssText = 'display:block;width:100%;height:100%';
        }
        this._faceSvg = svg;
        runtime.counters.changedNodes++;
      }
      runtime.counters.elementUpdates++;
      return;
    }
  };

  customElements.define('h6-clock', H6ClockElement);
  root.H6ClockRuntime = Runtime;
  root.__clockPerf = function () { return Runtime.inspect(); };
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState !== 'hidden') Runtime.refreshVisible('visibility');
    Runtime.syncVisibility();
  }, false);
  root.addEventListener('pageshow', function () { Runtime.refreshVisible('pageshow'); Runtime.syncVisibility(); }, false);
  root.addEventListener('focus', function () { Runtime.refreshVisible('focus'); Runtime.syncVisibility(); }, false);
  root.addEventListener('h6-clock-environment', function () { Runtime.environmentChanged(); }, false);
})(window);
