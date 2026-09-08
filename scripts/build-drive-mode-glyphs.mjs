#!/usr/bin/env node
/*
 * Flatten the drive-mode glyphs into a form both surfaces can draw.
 *
 * The web widget renders an SVG `d` string directly. The native rail card draws
 * into a Canvas, and a full SVG path parser on that side would be a liability —
 * so this script reduces each icon to the four commands a ten-line parser can
 * handle: absolute M, L, C and Z. Relative commands are resolved, shorthand
 * (H V S T Q) is expanded, and elliptical arcs are converted to cubics here,
 * once, instead of on the head unit every frame.
 *
 * Source icons: Tabler Icons (MIT), 24x24 grid, 2px stroke — the same grid the
 * card glyphs already used. Vendored as path data rather than fetched at build
 * time so the build stays offline and the exact art is reviewable in-repo.
 *
 *   Tabler Icons — Copyright (c) 2020-2024 Paweł Kuna — MIT License
 *   https://github.com/tabler/tabler-icons
 *
 * Run: node scripts/build-drive-mode-glyphs.mjs
 * Paste the output over CAR_DRIVE_MODE_GLYPHS in index.html.
 */

// Raw Tabler path data, concatenated per icon, decorative bounding paths removed.
const ICONS = {
  '2': { name: 'leaf', d: 'M5 21c.5 -4.5 2.5 -8 7 -10 M9 18c6.218 0 10.5 -3.288 11 -12v-2h-4.014c-9 0 -11.986 4 -12 9c0 1 0 3 2 5h3l.014 0' },
  '0': { name: 'road', d: 'M4 19l4 -14 M16 5l4 14 M12 8v-2 M12 13v-2 M12 18v-2' },
  '1': { name: 'bolt', d: 'M13 3l0 7l6 0l-8 11l0 -7l-6 0l8 -11' },
  '3': { name: 'snowflake', d: 'M10 4l2 1l2 -1 M12 2v6.5l3 1.72 M17.928 6.268l.134 2.232l1.866 1.232 M20.66 7l-5.629 3.25l.01 3.458 M19.928 14.268l-1.866 1.232l-.134 2.232 M20.66 17l-5.629 -3.25l-2.99 1.738 M14 20l-2 -1l-2 1 M12 22v-6.5l-3 -1.72 M6.072 17.732l-.134 -2.232l-1.866 -1.232 M3.34 17l5.629 -3.25l-.01 -3.458 M4.072 9.732l1.866 -1.232l.134 -2.232 M3.34 7l5.629 3.25l2.99 -1.738' },
  '4': { name: 'ripple', d: 'M3 7c3 -2 6 -2 9 0s6 2 9 0 M3 17c3 -2 6 -2 9 0s6 2 9 0 M3 12c3 -2 6 -2 9 0s6 2 9 0' },
  '5': { name: 'droplets', d: 'M4.072 20.3a2.999 2.999 0 0 0 3.856 0a3.002 3.002 0 0 0 .67 -3.798l-2.095 -3.227a.6 .6 0 0 0 -1.005 0l-2.098 3.227a3.003 3.003 0 0 0 .671 3.798 M16.072 20.3a2.999 2.999 0 0 0 3.856 0a3.002 3.002 0 0 0 .67 -3.798l-2.095 -3.227a.6 .6 0 0 0 -1.005 0l-2.098 3.227a3.003 3.003 0 0 0 .671 3.798 M10.072 10.3a2.999 2.999 0 0 0 3.856 0a3.002 3.002 0 0 0 .67 -3.798l-2.095 -3.227a.6 .6 0 0 0 -1.005 0l-2.098 3.227a3.003 3.003 0 0 0 .671 3.798l.001 0' },
  // AWD has no entry: no icon set has a mark that says 'all four wheels are
  // driven' and survives 52px. It is drawn as the boxed code 4x4 instead, the
  // same treatment the POWER card gives HEV / EVP / EV. See CAR_DRIVE_MODE_BADGES.
};

/**
 * Single-glyph marks, emitted as their own constants. Steering assist shows its
 * wheel only on the selected option — the row is one of three that contain
 * "Normal", so it needs a mark, but three copies of the same wheel would say
 * nothing about which one is chosen.
 */
const SINGLE = {
  CAR_STEER_GLYPH: {
    name: 'steering-wheel',
    d: 'M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M12 14l0 7 M10 12l-6.75 -2 M14 12l6.75 -2',
  },
};

/** Split path data into [command, ...numbers] tokens. */
function tokenize(d) {
  const out = [];
  const re = /([MmLlHhVvCcSsQqTtAaZz])|(-?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?)/g;
  let m;
  while ((m = re.exec(d))) out.push(m[1] || parseFloat(m[2]));
  const cmds = [];
  let i = 0;
  while (i < out.length) {
    if (typeof out[i] !== 'string') throw new Error('path does not start with a command: ' + d);
    const letter = out[i++];
    const args = [];
    while (i < out.length && typeof out[i] === 'number') args.push(out[i++]);
    cmds.push([letter, args]);
  }
  return cmds;
}

const ARG_COUNT = { M: 2, L: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, T: 2, A: 7, Z: 0 };

/**
 * Elliptical arc to cubic béziers, endpoint -> centre parameterisation
 * (SVG spec appendix F.6). Split so no segment spans more than 90 degrees,
 * which keeps the cubic approximation under a thousandth of a pixel here.
 */
function arcToCubics(x1, y1, rx, ry, angleDeg, largeArc, sweep, x2, y2) {
  if (x1 === x2 && y1 === y2) return [];
  if (!rx || !ry) return [['L', [x2, y2]]];
  const phi = (angleDeg * Math.PI) / 180;
  const cos = Math.cos(phi);
  const sin = Math.sin(phi);
  const dx2 = (x1 - x2) / 2;
  const dy2 = (y1 - y2) / 2;
  const x1p = cos * dx2 + sin * dy2;
  const y1p = -sin * dx2 + cos * dy2;
  rx = Math.abs(rx); ry = Math.abs(ry);
  // Scale radii up when they are too small to span the endpoints (spec F.6.6).
  const lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
  if (lambda > 1) { const s = Math.sqrt(lambda); rx *= s; ry *= s; }
  const sign = largeArc === sweep ? -1 : 1;
  const num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
  const den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
  const co = sign * Math.sqrt(Math.max(0, num / den));
  const cxp = (co * rx * y1p) / ry;
  const cyp = (-co * ry * x1p) / rx;
  const cx = cos * cxp - sin * cyp + (x1 + x2) / 2;
  const cy = sin * cxp + cos * cyp + (y1 + y2) / 2;
  const angle = (ux, uy, vx, vy) => {
    const d = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
    let c = (ux * vx + uy * vy) / d;
    c = Math.min(1, Math.max(-1, c));
    return (ux * vy - uy * vx < 0 ? -1 : 1) * Math.acos(c);
  };
  const theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
  let delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
  if (!sweep && delta > 0) delta -= 2 * Math.PI;
  if (sweep && delta < 0) delta += 2 * Math.PI;

  const segments = Math.ceil(Math.abs(delta / (Math.PI / 2)));
  const step = delta / segments;
  const out = [];
  const point = (t) => {
    const ct = Math.cos(t);
    const st = Math.sin(t);
    return [cos * rx * ct - sin * ry * st + cx, sin * rx * ct + cos * ry * st + cy];
  };
  const deriv = (t) => {
    const ct = Math.cos(t);
    const st = Math.sin(t);
    return [-cos * rx * st - sin * ry * ct, -sin * rx * st + cos * ry * ct];
  };
  for (let i = 0; i < segments; i++) {
    const t1 = theta1 + i * step;
    const t2 = t1 + step;
    const alpha = (4 / 3) * Math.tan((t2 - t1) / 4);
    const [px1, py1] = point(t1);
    const [dx1, dy1] = deriv(t1);
    const [px2, py2] = point(t2);
    const [dx2b, dy2b] = deriv(t2);
    out.push(['C', [
      px1 + alpha * dx1, py1 + alpha * dy1,
      px2 - alpha * dx2b, py2 - alpha * dy2b,
      px2, py2,
    ]]);
  }
  return out;
}

/** Reduce a path to absolute M / L / C / Z. */
function flatten(d) {
  const out = [];
  let x = 0, y = 0, startX = 0, startY = 0;
  let lastCtrl = null;
  let lastQCtrl = null;
  let prev = '';
  for (const [rawLetter, args] of tokenize(d)) {
    const abs = rawLetter.toUpperCase();
    const rel = rawLetter !== abs;
    const n = ARG_COUNT[abs];
    // A command letter can be followed by several argument sets; a repeated
    // moveto's extra pairs are linetos, per the SVG grammar.
    const groups = n === 0 ? [[]] : [];
    for (let i = 0; i < args.length; i += n) groups.push(args.slice(i, i + n));
    groups.forEach((g, gi) => {
      let letter = abs;
      if (abs === 'M' && gi > 0) letter = 'L';
      switch (letter) {
        case 'M': {
          x = rel ? x + g[0] : g[0]; y = rel ? y + g[1] : g[1];
          startX = x; startY = y; out.push(['M', [x, y]]); break;
        }
        case 'L': {
          x = rel ? x + g[0] : g[0]; y = rel ? y + g[1] : g[1];
          out.push(['L', [x, y]]); break;
        }
        case 'H': { x = rel ? x + g[0] : g[0]; out.push(['L', [x, y]]); break; }
        case 'V': { y = rel ? y + g[0] : g[0]; out.push(['L', [x, y]]); break; }
        case 'C': {
          const c1x = rel ? x + g[0] : g[0], c1y = rel ? y + g[1] : g[1];
          const c2x = rel ? x + g[2] : g[2], c2y = rel ? y + g[3] : g[3];
          const ex = rel ? x + g[4] : g[4], ey = rel ? y + g[5] : g[5];
          out.push(['C', [c1x, c1y, c2x, c2y, ex, ey]]);
          lastCtrl = [c2x, c2y]; x = ex; y = ey; break;
        }
        case 'S': {
          const reflect = 'CS'.includes(prev) && lastCtrl
            ? [2 * x - lastCtrl[0], 2 * y - lastCtrl[1]] : [x, y];
          const c2x = rel ? x + g[0] : g[0], c2y = rel ? y + g[1] : g[1];
          const ex = rel ? x + g[2] : g[2], ey = rel ? y + g[3] : g[3];
          out.push(['C', [reflect[0], reflect[1], c2x, c2y, ex, ey]]);
          lastCtrl = [c2x, c2y]; x = ex; y = ey; break;
        }
        case 'Q':
        case 'T': {
          let qx, qy, ex, ey;
          if (letter === 'Q') {
            qx = rel ? x + g[0] : g[0]; qy = rel ? y + g[1] : g[1];
            ex = rel ? x + g[2] : g[2]; ey = rel ? y + g[3] : g[3];
          } else {
            const r = 'QT'.includes(prev) && lastQCtrl
              ? [2 * x - lastQCtrl[0], 2 * y - lastQCtrl[1]] : [x, y];
            qx = r[0]; qy = r[1];
            ex = rel ? x + g[0] : g[0]; ey = rel ? y + g[1] : g[1];
          }
          // Quadratic -> cubic: control points at two thirds toward the quad's.
          out.push(['C', [
            x + (2 / 3) * (qx - x), y + (2 / 3) * (qy - y),
            ex + (2 / 3) * (qx - ex), ey + (2 / 3) * (qy - ey),
            ex, ey,
          ]]);
          lastQCtrl = [qx, qy]; x = ex; y = ey; break;
        }
        case 'A': {
          const ex = rel ? x + g[5] : g[5], ey = rel ? y + g[6] : g[6];
          for (const seg of arcToCubics(x, y, g[0], g[1], g[2], !!g[3], !!g[4], ex, ey)) {
            out.push(seg);
          }
          x = ex; y = ey; break;
        }
        case 'Z': { out.push(['Z', []]); x = startX; y = startY; break; }
        default: throw new Error('unsupported command ' + letter);
      }
      prev = letter;
    });
  }
  return out;
}

const round = (n) => {
  const r = Math.round(n * 100) / 100;
  return Object.is(r, -0) ? 0 : r;
};

function serialise(cmds) {
  // A space after every command letter so the native side can split on
  // whitespace instead of carrying a tokenizer.
  return cmds.map(([c, a]) => (a.length ? c + ' ' + a.map(round).join(' ') : c)).join(' ').trim();
}

const lines = [];
for (const [value, icon] of Object.entries(ICONS)) {
  const flat = serialise(flatten(icon.d));
  if (/[^MLCZ0-9eE.\-\s]/.test(flat)) throw new Error('unflattened command left in ' + icon.name);
  lines.push(`  '${value}': '${flat}',   // tabler ${icon.name}`);
}
console.log('const CAR_DRIVE_MODE_GLYPHS = {');
console.log(lines.join('\n'));
console.log('};');
for (const [name, icon] of Object.entries(SINGLE)) {
  const flat = serialise(flatten(icon.d));
  if (/[^MLCZ0-9eE.\-\s]/.test(flat)) throw new Error('unflattened command left in ' + icon.name);
  console.log(`const ${name} = '${flat}';   // tabler ${icon.name}`);
}
