(function (root) {
  'use strict';

  // Complete SVG clock faces for cards and widgets.
  // Native must display a rasterization of this SVG, never an independently drawn approximation.
  // One exception, and it is the same geometry, not an approximation: the
  // panorama seconds sweep. Re-encoding the whole face every second cost the
  // main thread 25-160 ms/s on the car (a 1 Hz hitch in the 3D view), so the
  // card face is rasterized with omitSweep once a minute and native strokes
  // the exact path that sweep() describes.
  var names = { panorama: 'Orbit', meridian: 'Chronograph', split: 'Monogram', 'date-spine': 'Dashboard' };
  function escape(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c];
    });
  }
  function color(value, fallback) { return /^#[0-9a-f]{6}$/i.test(value || '') ? value : fallback; }

  function render(face, snap, options) {
    options = options || {};
    face = names[face] ? face : 'panorama';
    var size = options.size || 'rail';
    if (size !== '1x1' && size !== '1x2' && size !== '2x2' && size !== '2x1') size = 'rail';

    var light = options.light === true;
    var ink = light ? '#152829' : '#f4f3ed';
    var muted = light ? '#4e6365' : '#aebdc2';
    var accent = color(options.accent, light ? '#087c70' : '#8cebc9');
    var ground = light ? '#e9f0ed' : '#10191d';
    var edge = light ? '#b8ccc8' : '#334449';
    var out = [];

    var W = 224, H = 124;
    if (size === '2x1') { W = 296; H = 124; }
    else if (size === '1x1') { W = 146; H = 124; }
    else if (size === '1x2') { W = 124; H = 214; }
    else if (size === '2x2') { W = 244; H = 208; }
    else { W = 224; H = 124; }
    var svgWidth = W * 2;
    var svgHeight = H * 2;

    function rect(x, y, w, h, r, fill, stroke, width) {
      out.push('<rect x="' + x + '" y="' + y + '" width="' + w + '" height="' + h + '" rx="' + r + '" fill="' + fill + '"' + (stroke ? ' stroke="' + stroke + '" stroke-width="' + (width || 1) + '"' : '') + '/>');
    }
    function text(value, x, y, textSize, fill, weight, anchor, maxWidth, family) {
      out.push('<text x="' + x + '" y="' + y + '" fill="' + (fill || ink) + '" font-family="' + (family || 'Arial, sans-serif') + '" font-size="' + textSize + '" font-weight="' + (weight || 700) + '" text-anchor="' + (anchor || 'start') + '"' + (maxWidth ? ' textLength="' + maxWidth + '" lengthAdjust="spacingAndGlyphs"' : '') + '>' + escape(value) + '</text>');
    }
    function label(value, x, y, size, fill, weight, anchor, maxWidth) {
      var s = String(value == null ? '' : value);
      var em = 0;
      for (var k = 0; k < s.length; k++) {
        var ch = s.charAt(k);
        em += /[0-9]/.test(ch) ? 0.556 : (ch === ' ' ? 0.278 : ch === ':' ? 0.333 : (/[A-ZÀ-Ý]/.test(ch) ? 0.72 : 0.6));
      }
      var fitted = em > 0 && em * size > maxWidth ? Math.floor(maxWidth / em * 10) / 10 : size;
      text(s, x, y, fitted, fill, weight, anchor);
    }
    function line(x1, y1, x2, y2, stroke, width) {
      out.push('<line x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '" stroke="' + stroke + '" stroke-width="' + width + '" stroke-linecap="round"/>');
    }
    function circle(x, y, r, fill, stroke, width) {
      out.push('<circle cx="' + x + '" cy="' + y + '" r="' + r + '" fill="' + fill + '"' + (stroke ? ' stroke="' + stroke + '" stroke-width="' + (width || 1) + '"' : '') + '/>');
    }

    var dateLabel = [snap.weekdayShort, snap.day, snap.monthShort].join(' ').toUpperCase();
    var period = snap.dayPeriod || '';

    out.push('<svg xmlns="http://www.w3.org/2000/svg" width="' + svgWidth + '" height="' + svgHeight + '" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="' + escape(snap.accessible) + '"><defs>'
      + '<linearGradient id="base" x2="0.9" y2="1"><stop stop-color="' + (light ? '#f7faf8' : '#1c292e') + '"/><stop offset="1" stop-color="' + ground + '"/></linearGradient>'
      + '<radialGradient id="metal"><stop stop-color="' + (light ? '#c2d1cf' : '#3c494c') + '"/><stop offset="0.22" stop-color="' + ground + '"/><stop offset="0.66" stop-color="' + (light ? '#e6eeeb' : '#263237') + '"/><stop offset="1" stop-color="' + ground + '"/></radialGradient>'
      + '<radialGradient id="halo"><stop stop-color="' + accent + '" stop-opacity="0.18"/><stop offset="1" stop-color="' + accent + '" stop-opacity="0"/></radialGradient>'
      + '<clipPath id="bounds"><rect width="' + W + '" height="' + H + '" rx="14"/></clipPath></defs>'
      + '<g clip-path="url(#bounds)" font-family="Arial, sans-serif" font-variant="tabular-nums">');

    function drawDial(cx, cy, r) {
      circle(cx, cy, r, 'url(#metal)', edge, 0.8);
      for (var ring = r * 0.33; ring < r * 0.91; ring += 1.8) {
        circle(cx, cy, ring, 'none', light ? '#b8c9c6' : '#344044', 0.25);
      }
      var marks = options.dialMarks !== 'plain';
      for (var i = 0; i < 60; i++) {
        if (!marks && i % 15 !== 0) continue;
        var a = i * Math.PI / 30 - Math.PI / 2;
        var radius = i % 15 === 0 ? r * 0.76 : (i % 5 === 0 ? r * 0.815 : r * 0.87);
        var rOuter = r * 0.926;
        line(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius, cx + Math.cos(a) * rOuter, cy + Math.sin(a) * rOuter, i % 5 === 0 ? ink : muted, i % 15 === 0 ? 2.2 : 0.8);
      }
      var aHour = (snap.hourAngle - 90) * Math.PI / 180;
      line(cx - Math.cos(aHour) * 5, cy - Math.sin(aHour) * 5, cx + Math.cos(aHour) * (r * 0.52), cy + Math.sin(aHour) * (r * 0.52), ink, Math.max(2.8, r * 0.078));
      var aMin = (snap.minuteAngle - 90) * Math.PI / 180;
      line(cx - Math.cos(aMin) * 5, cy - Math.sin(aMin) * 5, cx + Math.cos(aMin) * (r * 0.76), cy + Math.sin(aMin) * (r * 0.76), ink, Math.max(1.8, r * 0.052));
      circle(cx, cy, Math.max(2.6, r * 0.067), accent, ground, 1.5);
    }

    if (face === 'meridian') {
      // Precision chronograph dial with digital time readout
      if (size === '1x1') {
        drawDial(73, 50, 42);
        text(snap.time, 73, 106, 20, ink, 700, 'middle');
        label(dateLabel, 73, 118, 8, muted, 600, 'middle', 110);
      } else if (size === '1x2') {
        drawDial(62, 64, 50);
        line(14, 124, 110, 124, edge, 0.8);
        text(snap.time, 62, 158, 32, ink, 700, 'middle');
        if (period) text(period, 110, 142, 8, accent, 700, 'end');
        text(String(snap.weekdayShort).toUpperCase(), 62, 178, 10, muted, 600, 'middle');
        text(snap.day + ' ' + String(snap.monthShort).toUpperCase(), 62, 198, 13, ink, 700, 'middle');
      } else if (size === '2x2') {
        // Dial and readout share 244 units: the column right of the divider
        // must hold "10:30" at 38 and "SEPTEMBER 2026", or they run off the card.
        drawDial(64, 104, 54);
        line(128, 26, 128, 182, edge, 0.8);
        label(snap.time, 138, 90, 38, ink, 700, 'start', 98);
        if (period) text(period, 236, 58, 10, accent, 700, 'end');
        label(String(snap.weekdayLong || snap.weekdayShort).toUpperCase(), 138, 118, 12, muted, 600, 'start', 98);
        text(snap.day, 138, 156, 36, ink, 700, 'start');
        label(String(snap.monthLong || snap.monthShort).toUpperCase() + ' ' + snap.year, 138, 176, 11, muted, 600, 'start', 98);
      } else if (size === '2x1') {
        // Wide 2x1 card (296x124)
        drawDial(72, 62, 54);
        line(146, 16, 146, 108, edge, 0.8);
        text(snap.time, 160, 68, 44, ink, 700, 'start');
        if (period) text(period, 282, 52, 9, accent, 700, 'end');
        label(dateLabel, 160, 92, 11, muted, 600, 'start', 122);
      } else {
        // Standard rail card (224x124)
        drawDial(58, 62, 54);
        text(snap.time, 119, 70, 34, ink, 700, 'start', 96);
        label(dateLabel, 119, 91, 9, muted, 600, 'start', 94);
        if (period) text(period, 215, 39, 10, accent, 700, 'end');
      }
    } else if (face === 'panorama') {
      // Bold time with live circular/perimeter seconds sweep
      var hour24 = typeof options.hour24 === 'number' ? options.hour24 : Number(snap.hour);
      var dayProgress = Math.max(0, Math.min(1, (hour24 * 60 + Number(snap.minute)) / 1440));
      var secondProgress = Math.max(0, Math.min(1, Number(snap.second || 0) / 60));
      var pad = (size === '2x1' || size === '1x1') ? 5 : 4;
      var rOuter = (size === '2x1' || size === '1x1') ? 8 : 16;
      var oW = W - 2 * pad, oH = H - 2 * pad;
      var oLen = 2 * (oW + oH - 4 * rOuter) + 2 * Math.PI * rOuter;
      var iW = oW - 10, iH = oH - 10, rInner = Math.max(4, rOuter - 2);
      var iLen = 2 * (iW + iH - 4 * rInner) + 2 * Math.PI * rInner;

      out.push('<ellipse cx="' + (W / 2) + '" cy="' + (H * 0.4) + '" rx="' + (W * 0.48) + '" ry="' + (H * 0.6) + '" fill="url(#halo)"/>');
      rect(pad, pad, oW, oH, rOuter, 'none', edge, 2.8);
      if (!options.omitSweep) out.push('<rect x="' + pad + '" y="' + pad + '" width="' + oW + '" height="' + oH + '" rx="' + rOuter + '" fill="none" stroke="' + accent + '" stroke-width="' + SWEEP_STROKE + '" stroke-dasharray="' + (secondProgress * oLen).toFixed(3) + ' ' + oLen.toFixed(3) + '" stroke-linecap="round"/>');
      out.push('<rect x="' + (pad + 5) + '" y="' + (pad + 5) + '" width="' + iW + '" height="' + iH + '" rx="' + rInner + '" fill="none" stroke="' + accent + '" opacity="0.22" stroke-width="1.5" stroke-dasharray="' + (dayProgress * iLen).toFixed(3) + ' ' + iLen.toFixed(3) + '" stroke-linecap="round"/>');

      if (size === '1x1') {
        text(snap.time, 73, 64, 40, ink, 700, 'middle');
        if (period) text(period, 73, 79, 8, accent, 700, 'middle');
        rect(26, 86, 94, 18, 9, ground, edge, 0.8);
        label(dateLabel, 73, 99, 8.5, muted, 600, 'middle', 82);
      } else if (size === '1x2') {
        text(String(snap.weekdayShort).toUpperCase(), 62, 58, 12, muted, 600, 'middle');
        text(snap.time, 62, 114, 44, ink, 700, 'middle');
        if (period) text(period, 62, 134, 9, accent, 700, 'middle');
        rect(14, 150, 96, 22, 11, ground, edge, 0.8);
        label(snap.day + ' ' + String(snap.monthShort).toUpperCase() + ' ' + snap.year, 62, 165, 9.5, muted, 600, 'middle', 84);
      } else if (size === '2x2') {
        text(snap.time, 122, 104, 70, ink, 700, 'middle');
        if (period) text(period, 210, 62, 11, accent, 700);
        rect(57, 130, 130, 24, 12, ground, edge, 0.8);
        label(dateLabel + ' ' + snap.year, 122, 146, 11, muted, 600, 'middle', 118);
      } else if (size === '2x1') {
        // Wide 2x1 card (296x124)
        text(snap.time, 148, 76, 62, ink, 700, 'middle');
        rect(88, 88, 120, 20, 10, ground, edge, 0.8);
        label(dateLabel, 148, 102, 10.5, muted, 600, 'middle', 104);
        if (period) text(period, 218, 102, 9, accent, 700, 'start');
      } else {
        // Standard rail card (224x124)
        text(snap.time, 112, 76, 60, ink, 700, 'middle', 180);
        rect(55, 88, 114, 19, 9.5, ground, edge, 0.8);
        label(dateLabel, 112, 101, 10, muted, 600, 'middle', 94);
        if (period) text(period, 184, 99, 9, accent, 700);
      }
    } else if (face === 'split') {
      // Bold stacked numerals with diagonal facets
      out.push('<path d="M-30 ' + H + ' L70 0 H108 L8 ' + H + ' Z M115 ' + H + ' L215 0 H240 L140 ' + H + ' Z" fill="' + ink + '" opacity="0.028"/>');
      if (size === '1x1') {
        out.push('<g transform="translate(14 16) scale(0.68)">');
        text(snap.hour, 0, 48, 66, ink, 800, 'start');
        text(snap.minute, 0, 103, 66, accent, 800, 'start');
        out.push('</g>');
        line(92, 16, 92, 108, edge, 1);
        text(String(snap.weekdayShort).toUpperCase(), 118, 40, 9, muted, 600, 'middle');
        text(snap.day, 118, 72, 28, ink, 700, 'middle');
        text(String(snap.monthShort).toUpperCase(), 118, 93, 9, muted, 600, 'middle');
        if (period) text(period, 118, 108, 6.5, muted, 600, 'middle');
      } else if (size === '1x2') {
        out.push('<g transform="translate(15 16) scale(0.96)">');
        text(snap.hour, 49, 48, 64, ink, 800, 'middle');
        text(snap.minute, 49, 104, 64, accent, 800, 'middle');
        out.push('</g>');
        line(16, 130, 108, 130, edge, 1);
        text(String(snap.weekdayShort).toUpperCase(), 62, 154, 11, muted, 600, 'middle');
        text(snap.day, 62, 186, 36, ink, 700, 'middle');
        text(String(snap.monthShort).toUpperCase(), 62, 204, 11, muted, 600, 'middle');
        if (period) text(period, 105, 152, 8, accent, 700, 'end');
      } else if (size === '2x2') {
        // Digits span 46..164, centred on the divider (24..184) like the
        // date column; the period rides on the minutes' baseline beside them.
        out.push('<g transform="translate(34 45) scale(1.15)">');
        text(snap.hour, 0, 48, 66, ink, 800, 'start');
        text(snap.minute, 0, 103, 66, accent, 800, 'start');
        out.push('</g>');
        line(156, 24, 156, 184, edge, 1);
        text(String(snap.weekdayShort).toUpperCase(), 195, 68, 14, muted, 600, 'middle');
        text(snap.day, 195, 120, 50, ink, 700, 'middle');
        text(String(snap.monthShort).toUpperCase(), 195, 151, 14, muted, 600, 'middle');
        if (period) text(period, 128, 163, 11, muted, 700, 'start');
      } else if (size === '2x1') {
        // Wide 2x1 card (296x124)
        out.push('<g transform="translate(42 18) scale(0.85)">');
        text(snap.hour, 0, 48, 66, ink, 800, 'start');
        text(snap.minute, 0, 103, 66, accent, 800, 'start');
        out.push('</g>');
        line(148, 20, 148, 104, edge, 1);
        text(String(snap.weekdayShort).toUpperCase(), 222, 40, 12, muted, 600, 'middle');
        text(snap.day, 222, 76, 36, ink, 700, 'middle');
        text(String(snap.monthShort).toUpperCase(), 222, 98, 12, muted, 600, 'middle');
        if (period) text(period, 222, 114, 8, muted, 600, 'middle');
      } else {
        // Standard rail card (224x124)
        out.push('<g transform="translate(29.2 20) scale(0.8)">');
        text(snap.hour, 0, 48, 66, ink, 800, 'start', 117);
        text(snap.minute, 0, 103, 66, accent, 800, 'start', 117);
        out.push('</g>');
        line(142, 24, 142, 100, edge, 1);
        text(String(snap.weekdayShort).toUpperCase(), 181, 38, 11, muted, 600, 'middle');
        text(snap.day, 181, 77, 36, ink, 700, 'middle');
        text(String(snap.monthShort).toUpperCase(), 181, 98, 11, muted, 600, 'middle');
        if (period) text(period, 181, 114, 7, muted, 600, 'middle');
      }
    } else {
      // date-spine: Dashboard with Sunday-first calendar strip
      var p = String(snap.dateKey).split('-').map(Number);
      var today = new Date(Date.UTC(p[0], p[1] - 1, p[2]));
      var offset = today.getUTCDay();

      if (size === '1x1') {
        text(snap.time, 12, 48, 30, ink, 700, 'start');
        label(String(snap.weekdayShort).toUpperCase(), 122, 29, 8.5, muted, 600, 'middle', 40);
        rect(106, 36, 32, 16, 5, accent);
        label(String(snap.monthShort).toUpperCase(), 122, 48, 9, ground, 700, 'middle', 28);
        line(10, 62, 136, 62, edge, 0.8);
        for (var d0 = 0; d0 < 7; d0++) {
          var c0 = new Date(today.getTime() + (d0 - offset) * 86400000);
          var x0 = 10 + d0 * 18;
          if (d0 === offset) rect(x0, 74, 16, 22, 5, accent);
          var isW0 = c0.getUTCDay() === 0 || c0.getUTCDay() === 6;
          text(String(c0.getUTCDate()).padStart(2, '0'), x0 + 8, 89, 9, d0 === offset ? ground : (isW0 ? accent : muted), d0 === offset ? 700 : 500, 'middle');
        }
        if (period) text(period, 96, 58, 7, muted, 600, 'end');
      } else if (size === '1x2') {
        label(String(snap.weekdayLong || snap.weekdayShort).toUpperCase(), 62, 34, 11, muted, 600, 'middle', 100);
        rect(42, 46, 40, 20, 6, accent);
        label(String(snap.monthShort).toUpperCase(), 62, 60, 10, ground, 700, 'middle', 32);
        text(snap.time, 62, 106, 42, ink, 700, 'middle');
        if (period) text(period, 112, 88, 8, accent, 700, 'end');
        line(12, 124, 112, 124, edge, 0.8);
        for (var d1 = 0; d1 < 7; d1++) {
          var c1 = new Date(today.getTime() + (d1 - offset) * 86400000);
          var x1 = 8 + d1 * 15.5;
          if (d1 === offset) rect(x1, 138, 14, 24, 5, accent);
          var isW1 = c1.getUTCDay() === 0 || c1.getUTCDay() === 6;
          text(String(c1.getUTCDate()).padStart(2, '0'), x1 + 7, 154, 9, d1 === offset ? ground : (isW1 ? accent : muted), d1 === offset ? 700 : 500, 'middle');
        }
        text(snap.day + ' ' + String(snap.monthLong || snap.monthShort) + ' ' + snap.year, 62, 190, 10, muted, 600, 'middle');
      } else if (size === '2x2') {
        // Time + week strip only (the spelled-out date repeated both), centred
        // vertically: weekday cap top 49 to strip bottom 158 in 208. A 12h
        // time shrinks to leave the period room before the month pill at 178.
        label(snap.time, 18, 92, 54, ink, 700, 'start', period ? 128 : 150);
        if (period) text(period, 150, 92, 10, muted, 600, 'start');
        label(String(snap.weekdayLong || snap.weekdayShort).toUpperCase(), 204, 58, 12, muted, 600, 'middle', 60);
        rect(178, 70, 52, 28, 8, accent);
        label(String(snap.monthShort).toUpperCase(), 204, 89, 13, ground, 700, 'middle', 36);
        line(16, 110, 228, 110, edge, 0.8);
        for (var d2 = 0; d2 < 7; d2++) {
          var c2 = new Date(today.getTime() + (d2 - offset) * 86400000);
          var x2 = 18 + d2 * 30;
          if (d2 === offset) rect(x2, 128, 26, 30, 7, accent);
          var isW2 = c2.getUTCDay() === 0 || c2.getUTCDay() === 6;
          text(String(c2.getUTCDate()).padStart(2, '0'), x2 + 13, 148, 13, d2 === offset ? ground : (isW2 ? accent : muted), d2 === offset ? 700 : 500, 'middle');
        }
      } else if (size === '2x1') {
        // Wide 2x1 card (296x124)
        text(snap.time, 18, 64, 48, ink, 700, 'start');
        if (period) text(period, 146, 64, 9, muted, 600, 'start');
        label(String(snap.weekdayShort).toUpperCase(), 252, 38, 11, muted, 600, 'middle', 54);
        rect(228, 48, 48, 22, 7, accent);
        label(String(snap.monthShort).toUpperCase(), 252, 64, 11, ground, 700, 'middle', 34);
        line(18, 78, 278, 78, edge, 0.8);
        for (var d = 0; d < 7; d++) {
          var cell = new Date(today.getTime() + (d - offset) * 86400000);
          var x = 18 + d * 37.5;
          if (d === offset) rect(x, 86, 33, 26, 7, accent);
          var isWeekend = cell.getUTCDay() === 0 || cell.getUTCDay() === 6;
          text(String(cell.getUTCDate()).padStart(2, '0'), x + 16.5, 103, 12, d === offset ? ground : (isWeekend ? accent : muted), d === offset ? 700 : 500, 'middle');
        }
      } else {
        // Standard rail card (224x124)
        text(snap.time, 12, 68, 48, ink, 700, 'start', 143);
        label(String(snap.weekdayShort).toUpperCase(), 186, 41, 10, muted, 600, 'middle', 47);
        rect(163, 54, 47, 24, 8, accent);
        label(String(snap.monthShort).toUpperCase(), 186.5, 70, 11, ground, 700, 'middle', 31);
        line(13, 81, 211, 81, edge, 0.8);
        for (var d = 0; d < 7; d++) {
          var cell = new Date(today.getTime() + (d - offset) * 86400000);
          var x = 15 + d * 28;
          if (d === offset) rect(x, 90, 25, 25, 8, accent);
          var isWeekend = cell.getUTCDay() === 0 || cell.getUTCDay() === 6;
          text(String(cell.getUTCDate()).padStart(2, '0'), x + 12.5, 107, 12, d === offset ? ground : (isWeekend ? accent : muted), d === offset ? 700 : 500, 'middle');
        }
        if (period) text(period, 152, 78, 8, muted, 600, 'end');
      }
    }
    out.push('</g></svg>');
    return out.join('');
  }

  var SWEEP_STROKE = 3;

  // The panorama seconds sweep, in viewBox units: a rounded rect stroked from
  // (x + rx, y) clockwise -- SVG's own rect path order -- for second/60 of its
  // length. null for every other face. Must stay in step with render().
  function sweep(face, options) {
    options = options || {};
    if ((names[face] ? face : 'panorama') !== 'panorama') return null;
    var size = options.size || 'rail';
    var W = 224, H = 124;
    if (size === '2x1') { W = 296; H = 124; }
    else if (size === '1x1') { W = 146; H = 124; }
    else if (size === '1x2') { W = 124; H = 214; }
    else if (size === '2x2') { W = 244; H = 208; }
    var small = size === '2x1' || size === '1x1';
    var pad = small ? 5 : 4;
    var light = options.light === true;
    return {
      viewW: W, viewH: H, x: pad, y: pad, w: W - 2 * pad, h: H - 2 * pad,
      rx: small ? 8 : 16, stroke: SWEEP_STROKE,
      color: color(options.accent, light ? '#087c70' : '#8cebc9')
    };
  }

  root.H6ClockFaces = { version: 3, width: 224, height: 124, names: names, render: render, sweep: sweep };
})(typeof window !== 'undefined' ? window : globalThis);
