(function (root) {
  'use strict';

  var CLOCK_FACES = ['meridian', 'panorama', 'split', 'date-spine'];
  var WIDGET_STYLES = ['meridian', 'panorama', 'split', 'date-spine', 'twin', 'atelier', 'signal', 'datebook'];
  var RAIL_FACES = ['panorama', 'meridian', 'split', 'date-spine'];
  var HOUR_FORMATS = ['system', '24', '12'];
  var DIAL_MARKS = ['index', 'plain'];
  var SPLIT_PLATES = ['frost', 'flat'];
  var SPINE_FORMATS = ['month-name', 'numeric'];
  var DATE_WORDING = ['short', 'long'];
  var formatters = {};

  function oneOf(value, allowed, fallback) {
    value = String(value == null ? '' : value).toLowerCase();
    return allowed.indexOf(value) >= 0 ? value : fallback;
  }

  function mapWidgetStyle(rawStyle) {
    var val = String(rawStyle == null ? '' : rawStyle).toLowerCase();
    if (val === 'twin' || val === 'atelier') return 'meridian';
    if (val === 'signal') return 'panorama';
    if (val === 'datebook') return 'date-spine';
    return oneOf(val, CLOCK_FACES, 'meridian');
  }

  function normalizeWidgetConfig(raw) {
    raw = raw && typeof raw === 'object' ? raw : {};
    return {
      version: 1,
      style: mapWidgetStyle(raw.style || raw.clockStyle || raw.face),
      hourFormat: oneOf(raw.hourFormat || raw.format, HOUR_FORMATS, 'system')
    };
  }

  function normalizeBottomConfig(raw) {
    raw = raw && typeof raw === 'object' ? raw : {};
    return {
      version: 2,
      face: oneOf(raw.face || raw.style, RAIL_FACES, 'panorama'),
      hourFormat: oneOf(raw.hourFormat || raw.format, HOUR_FORMATS, 'system'),
      dialMarks: oneOf(raw.dialMarks, DIAL_MARKS, 'index'),
      splitPlates: oneOf(raw.splitPlates, SPLIT_PLATES, 'frost'),
      dateSpineFormat: oneOf(raw.dateSpineFormat, SPINE_FORMATS, 'month-name'),
      dateWording: oneOf(raw.dateWording, DATE_WORDING, 'short')
    };
  }

  function formatter(locale, timeZone, options) {
    var tz = timeZone || '';
    var key = String(locale || '') + '|' + tz + '|' + JSON.stringify(options);
    if (!formatters[key]) {
      var opts = {};
      Object.keys(options || {}).forEach(function (name) { opts[name] = options[name]; });
      opts.calendar = 'gregory';
      if (tz) opts.timeZone = tz;
      formatters[key] = new Intl.DateTimeFormat(locale || undefined, opts);
    }
    return formatters[key];
  }

  function partsObject(date, locale, timeZone, options) {
    var parts = formatter(locale, timeZone, options).formatToParts(date);
    var out = {};
    for (var i = 0; i < parts.length; i++) {
      if (parts[i].type !== 'literal') out[parts[i].type] = parts[i].value;
    }
    return out;
  }

  function systemUses24Hour(locale) {
    try {
      var resolved = formatter(locale, '', { hour: 'numeric' }).resolvedOptions();
      if (typeof resolved.hour12 === 'boolean') return !resolved.hour12;
      return resolved.hourCycle === 'h23' || resolved.hourCycle === 'h24';
    } catch (e) {
      return true;
    }
  }

  function localComponents(date, locale, timeZone) {
    var p = partsObject(date, locale, timeZone, {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
    });
    return {
      year: parseInt(p.year, 10), month: parseInt(p.month, 10), day: parseInt(p.day, 10),
      hour: parseInt(p.hour, 10) % 24, minute: parseInt(p.minute, 10), second: parseInt(p.second, 10)
    };
  }

  function resolvedHourFormat(requested, system24) {
    var format = oneOf(requested, HOUR_FORMATS, 'system');
    if (format === 'system') return system24 ? '24' : '12';
    return format;
  }

  function timeParts(date, locale, timeZone, requested, system24) {
    var resolved = resolvedHourFormat(requested, system24);
    var opts = { hour: '2-digit', minute: '2-digit', hourCycle: resolved === '24' ? 'h23' : 'h12' };
    var formatted = formatter(locale, timeZone, opts).formatToParts(date);
    var hour = '', minute = '', dayPeriod = '', literal = ':';
    for (var i = 0; i < formatted.length; i++) {
      var p = formatted[i];
      if (p.type === 'hour') hour = p.value;
      else if (p.type === 'minute') minute = p.value;
      else if (p.type === 'dayPeriod') dayPeriod = p.value;
      else if (p.type === 'literal' && /[^\s]/.test(p.value)) literal = p.value.trim();
    }
    hour = String(hour).replace(/^24$/, '00').padStart(2, '0');
    minute = String(minute).padStart(2, '0');
    return {
      hour: hour,
      minute: minute,
      separator: literal || ':',
      digits: hour + ':' + minute,
      dayPeriod: dayPeriod,
      resolvedFormat: resolved
    };
  }

  function weekday(date, locale, timeZone, length) {
    return formatter(locale, timeZone, { weekday: length || 'short' }).format(date);
  }

  function monthName(date, locale, timeZone, length) {
    return formatter(locale, timeZone, { month: length || 'short' }).format(date);
  }

  function dateStrings(date, locale, timeZone) {
    return {
      numeric: formatter(locale, timeZone, { day: '2-digit', month: '2-digit' }).format(date),
      medium: formatter(locale, timeZone, { weekday: 'short', day: '2-digit', month: '2-digit' }).format(date),
      full: formatter(locale, timeZone, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(date),
      weekdayShort: weekday(date, locale, timeZone, 'short'),
      weekdayLong: weekday(date, locale, timeZone, 'long'),
      monthShort: monthName(date, locale, timeZone, 'short'),
      monthLong: monthName(date, locale, timeZone, 'long'),
      monthYear: formatter(locale, timeZone, { month: 'long', year: 'numeric' }).format(date),
      day: partsObject(date, locale, timeZone, { day: '2-digit' }).day || '',
      year: partsObject(date, locale, timeZone, { year: 'numeric' }).year || ''
    };
  }

  function angles(date, locale, timeZone) {
    var c = localComponents(date, locale, timeZone);
    return {
      minute: c.minute * 6,
      hour: (c.hour % 12) * 30 + c.minute * 0.5
    };
  }

  function snapshot(date, config, environment) {
    date = date instanceof Date ? date : new Date(date == null ? Date.now() : date);
    environment = environment || {};
    var locale = environment.locale || undefined;
    var timeZone = environment.timeZone || undefined;
    var system24 = typeof environment.system24 === 'boolean'
      ? environment.system24 : systemUses24Hour(locale);
    var requested = config && config.hourFormat ? config.hourFormat : 'system';
    var t = timeParts(date, locale, timeZone, requested, system24);
    var d = dateStrings(date, locale, timeZone);
    var a = angles(date, locale, timeZone);
    var local = localComponents(date, locale, timeZone);
    return {
      valid: !isNaN(date.getTime()),
      time: t.digits,
      hour: t.hour,
      minute: t.minute,
      second: local.second,
      separator: t.separator,
      dayPeriod: t.dayPeriod,
      resolvedFormat: t.resolvedFormat,
      dateNumeric: d.numeric,
      dateMedium: d.medium,
      dateFull: d.full,
      weekdayShort: d.weekdayShort,
      weekdayLong: d.weekdayLong,
      monthShort: d.monthShort,
      monthLong: d.monthLong,
      monthYear: d.monthYear,
      day: d.day,
      year: d.year,
      dateKey: local.year + '-' + String(local.month).padStart(2, '0') + '-' + String(local.day).padStart(2, '0'),
      minuteAngle: a.minute,
      hourAngle: a.hour,
      accessible: t.digits + (t.dayPeriod ? ' ' + t.dayPeriod : '') + ', ' + d.full
    };
  }

  function nextMinuteDelay(nowMs) {
    var value = Number(nowMs);
    if (!isFinite(value)) value = Date.now();
    return 60000 - (value % 60000) + 20;
  }

  function regionFromLocale(locale) {
    var match = String(locale || '').replace('_', '-').match(/-([A-Za-z]{2}|\d{3})(?:-|$)/);
    return match ? match[1].toUpperCase() : '';
  }

  function weekStart(locale) {
    var region = regionFromLocale(locale);
    var sunday = ['US', 'BR', 'CA', 'AU', 'NZ', 'JP', 'PH', 'ZA'];
    var saturday = ['AE', 'AF', 'BH', 'DJ', 'DZ', 'EG', 'IQ', 'IR', 'JO', 'KW', 'LY', 'OM', 'QA', 'SD', 'SY'];
    if (sunday.indexOf(region) >= 0) return 0;
    if (saturday.indexOf(region) >= 0) return 6;
    var monday = ['DE', 'AT', 'CH', 'FR', 'ES', 'PT', 'IT', 'NL', 'BE', 'DK', 'NO', 'SE', 'FI', 'PL', 'CZ', 'SK', 'HU', 'RO', 'GB', 'IE'];
    if (monday.indexOf(region) >= 0) return 1;
    return null;
  }

  function weekDates(date, locale, timeZone) {
    var first = weekStart(locale);
    if (first == null || timeZone) return [];
    var start = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    var delta = (start.getDay() - first + 7) % 7;
    start.setDate(start.getDate() - delta);
    var days = [];
    for (var i = 0; i < 7; i++) {
      var value = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
      var p = dateStrings(value, locale, '');
      days.push({
        key: value.getFullYear() + '-' + String(value.getMonth() + 1).padStart(2, '0') + '-' + String(value.getDate()).padStart(2, '0'),
        weekday: p.weekdayShort,
        day: p.day,
        label: p.dateFull
      });
    }
    return days;
  }

  root.H6ClockCore = {
    WIDGET_STYLES: WIDGET_STYLES.slice(),
    RAIL_FACES: RAIL_FACES.slice(),
    HOUR_FORMATS: HOUR_FORMATS.slice(),
    normalizeWidgetConfig: normalizeWidgetConfig,
    normalizeBottomConfig: normalizeBottomConfig,
    systemUses24Hour: systemUses24Hour,
    resolvedHourFormat: resolvedHourFormat,
    localComponents: localComponents,
    snapshot: snapshot,
    nextMinuteDelay: nextMinuteDelay,
    weekStart: weekStart,
    weekDates: weekDates,
    clearFormatterCache: function () { formatters = {}; }
  };
})(typeof window !== 'undefined' ? window : this);
