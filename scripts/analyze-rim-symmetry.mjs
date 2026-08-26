// Offline audit of every catalogue rim, mirroring the two measurements the
// viewer now makes at runtime (index.html):
//   - the rim's rotational repeat period, which decides at what speed a
//     spinning render starts to alias (freeze / run backwards) -- and hence
//     where the blur sprite has to take over;
//   - the outer lip's first-harmonic runout: how far the bounding-box frame
//     the rim is normalised into is tilted and offset from its real axis of
//     revolution, which is what makes a swapped rim look unbalanced.
//
// Usage: npm run analyze:rims
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import draco3d from 'draco3dgltf';
import fs from 'node:fs';
import path from 'node:path';

const DIR = process.argv[2] || 'assets/wheels';
const io = new NodeIO()
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({
    'draco3d.decoder': await draco3d.createDecoderModule(),
    'draco3d.encoder': await draco3d.createEncoderModule(),
  });

function mulMat(a, b) { // column-major 4x4, a*b
  const o = new Array(16);
  for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) {
    let s = 0;
    for (let k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
    o[c * 4 + r] = s;
  }
  return o;
}
function trs(t, r, s) {
  const [x, y, z, w] = r;
  const x2 = x + x, y2 = y + y, z2 = z + z;
  const xx = x * x2, xy = x * y2, xz = x * z2;
  const yy = y * y2, yz = y * z2, zz = z * z2;
  const wx = w * x2, wy = w * y2, wz = w * z2;
  return [
    (1 - (yy + zz)) * s[0], (xy + wz) * s[0], (xz - wy) * s[0], 0,
    (xy - wz) * s[1], (1 - (xx + zz)) * s[1], (yz + wx) * s[1], 0,
    (xz + wy) * s[2], (yz - wx) * s[2], (1 - (xx + yy)) * s[2], 0,
    t[0], t[1], t[2], 1,
  ];
}
function xform(m, p) {
  return [
    m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
    m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
    m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
  ];
}

function collect(doc) {
  const pts = [];
  const walk = (node, parent) => {
    const m = mulMat(parent, trs(node.getTranslation(), node.getRotation(), node.getScale()));
    const mesh = node.getMesh();
    if (mesh) for (const prim of mesh.listPrimitives()) {
      const pos = prim.getAttribute('POSITION');
      if (!pos) continue;
      const n = pos.getCount();
      const v = [0, 0, 0];
      for (let i = 0; i < n; i++) { pos.getElement(i, v); pts.push(xform(m, v)); }
    }
    for (const c of node.listChildren()) walk(c, m);
  };
  const ident = [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1];
  for (const scn of doc.getRoot().listScenes()) for (const n of scn.listChildren()) walk(n, ident);
  return pts;
}

// Least-squares circle fit of (x,y) pairs → centre.
function circleFit(xs, ys) {
  let Sx=0,Sy=0,Sxx=0,Syy=0,Sxy=0,Sxz=0,Syz=0,Sz=0; const n = xs.length;
  for (let i = 0; i < n; i++) {
    const x = xs[i], y = ys[i], z = x*x + y*y;
    Sx+=x; Sy+=y; Sxx+=x*x; Syy+=y*y; Sxy+=x*y; Sxz+=x*z; Syz+=y*z; Sz+=z;
  }
  const M = [[Sxx,Sxy,Sx,Sxz],[Sxy,Syy,Sy,Syz],[Sx,Sy,n,Sz]];
  for (let i = 0; i < 3; i++) {
    let p = i;
    for (let k = i+1; k < 3; k++) if (Math.abs(M[k][i]) > Math.abs(M[p][i])) p = k;
    if (Math.abs(M[p][i]) < 1e-14) return null;
    [M[i], M[p]] = [M[p], M[i]];
    for (let k = 0; k < 3; k++) if (k !== i) { const f = M[k][i]/M[i][i]; for (let j = i; j < 4; j++) M[k][j] -= f*M[i][j]; }
  }
  return [(M[0][3]/M[0][0])/2, (M[1][3]/M[1][1])/2];
}

const BINS = 1440;

// Circular autocorrelation of the angular mass profile at shift 2*PI/N, with
// linear interpolation so N need not divide BINS. We want the FINEST repeat,
// so scan N downward and take the LARGEST order that still matches: N-fold
// symmetry implies symmetry at every divisor of N too (a 40-spoke rim is
// trivially 2-fold), and it is the finest pitch — the smallest rotation that
// leaves the rim looking identical — that decides when a spinning render
// freezes or runs backwards.
function repeatPeriodDeg(profile, bar = 0.75) {
  const n = profile.length;
  const mean = profile.reduce((a, b) => a + b, 0) / n;
  const c = profile.map(v => v - mean);
  const r0 = c.reduce((a, b) => a + b * b, 0);
  if (r0 <= 1e-12) return { deg: 360, corr: 0, N: 1, weak: true };
  const corrAt = (N) => {
    const shift = n / N;
    const i0 = Math.floor(shift), fr = shift - i0;
    let acc = 0;
    for (let i = 0; i < n; i++) {
      const a1 = c[(i + i0) % n], a2 = c[(i + i0 + 1) % n];
      acc += c[i] * (a1 + (a2 - a1) * fr);
    }
    return acc / r0;
  };
  let bc = -2, bN = 1;
  for (let N = 40; N >= 2; N--) {
    const corr = corrAt(N);
    if (corr >= bar) return { deg: 360 / N, corr, N };
    if (corr > bc) { bc = corr; bN = N; }
  }
  return { deg: 360 / bN, corr: bc, N: bN, weak: true };
}

const files = fs.readdirSync(DIR).filter(f => f.endsWith('.glb')).sort();
const rows = [];
for (const f of files) {
  let doc;
  try { doc = await io.read(path.join(DIR, f)); }
  catch (e) { rows.push({ f, err: e.message.slice(0, 60) }); continue; }
  const pts = collect(doc);
  if (!pts.length) { rows.push({ f, err: 'no verts' }); continue; }

  // bbox + centre, exactly as the viewer normalises it
  const mn = [Infinity, Infinity, Infinity], mx = [-Infinity, -Infinity, -Infinity];
  for (const p of pts) for (let i = 0; i < 3; i++) { if (p[i] < mn[i]) mn[i] = p[i]; if (p[i] > mx[i]) mx[i] = p[i]; }
  const bboxC = [0,1,2].map(i => (mn[i] + mx[i]) / 2);
  const size = [0,1,2].map(i => mx[i] - mn[i]);

  // axle detection, same heuristic the viewer uses
  const diff = [Math.abs(size[1]-size[2]), Math.abs(size[0]-size[2]), Math.abs(size[0]-size[1])];
  const axle = diff.indexOf(Math.min(...diff));
  const [u, w] = [[1,2],[0,2],[0,1]][axle];

  // radial coords about the bbox centre
  const xs = pts.map(p => p[u] - bboxC[u]);
  const ys = pts.map(p => p[w] - bboxC[w]);
  let R = 0;
  for (let i = 0; i < xs.length; i++) { const r = Math.hypot(xs[i], ys[i]); if (r > R) R = r; }

  // True centre from a circle fit on the outer lip band (genuinely circular on
  // every rim here). The angular profile below is binned about THIS centre, not
  // the bbox centre, so an off-centre model can't smear its own spoke pattern.
  const bx = [], by = [];
  for (let i = 0; i < xs.length; i++) { const r = Math.hypot(xs[i], ys[i]); if (r > R * 0.93) { bx.push(xs[i]); by.push(ys[i]); } }
  const fit = bx.length > 30 ? circleFit(bx, by) : null;
  const cx = fit ? fit[0] : 0, cy = fit ? fit[1] : 0;

  // Lateral + radial runout of the outer lip, measured the way the viewer's
  // own normalisation now corrects it (index.html, _handleUploadedWheelModel
  // step 7b): fit the lip's axial coordinate as z ~ z0 + A*cos(t) + B*sin(t).
  // A seated wheel has A = B = 0; a tilt of the spin axis away from the rim's
  // real axis of revolution shows up as exactly this once-per-revolution term.
  // Deliberately NOT a PCA of the vertex cloud: several catalogue rims are not
  // clean discs (a caliper, an asymmetric hub) and the PCA axle on those is
  // noise, whereas the lip is a genuine circle on every one of them.
  let tiltDeg = NaN, offPctFit = NaN;
  {
    const n2 = [];
    for (let i = 0; i < xs.length; i++) {
      const r = Math.hypot(xs[i], ys[i]);
      if (r > R * 0.93) n2.push([xs[i] / r, ys[i] / r, pts[i][axle] - bboxC[axle], r]);
    }
    if (n2.length > 200) {
      let N = n2.length, Scc=0, Sss=0, Scs=0, Sc=0, Ss=0, Szc=0, Szs=0, Sz=0, Src=0, Srs=0, Sr=0;
      for (const [ct, st, z, r] of n2) {
        Scc+=ct*ct; Sss+=st*st; Scs+=ct*st; Sc+=ct; Ss+=st;
        Szc+=z*ct; Szs+=z*st; Sz+=z; Src+=r*ct; Srs+=r*st; Sr+=r;
      }
      const solve = (Syc, Sys, Sy) => {
        const M=[[Scc,Scs,Sc,Syc],[Scs,Sss,Ss,Sys],[Sc,Ss,N,Sy]];
        for (let i=0;i<3;i++){
          let p2=i; for(let k=i+1;k<3;k++) if(Math.abs(M[k][i])>Math.abs(M[p2][i])) p2=k;
          if(Math.abs(M[p2][i])<1e-14) return null; [M[i],M[p2]]=[M[p2],M[i]];
          for(let k=0;k<3;k++) if(k!==i){const f=M[k][i]/M[i][i]; for(let j=i;j<4;j++) M[k][j]-=f*M[i][j];}
        }
        return [M[0][3]/M[0][0], M[1][3]/M[1][1], M[2][3]/M[2][2]];
      };
      const zf = solve(Szc, Szs, Sz), rf = solve(Src, Srs, Sr);
      if (zf && rf) {
        const rBand = rf[2];
        tiltDeg = Math.atan2(Math.hypot(zf[0], zf[1]), rBand) * 180 / Math.PI;
        offPctFit = (Math.hypot(rf[0], rf[1]) / rBand) * 100;
      }
    }
  }

  // Angular mass profile over the spoke annulus. Vertex count per bin is a
  // usable stand-in for "how much rim material sits at this angle"; the sqrt
  // keeps a densely-tessellated detail (a logo, a lug boss) from dominating
  // the pattern the spokes make. A pure occupancy mask was tried and is worse
  // here: at these radii many rims are opaque from some layer or other at
  // every angle, so the mask saturates to a constant and the spokes vanish.
  const raw = new Float64Array(BINS);
  for (let i = 0; i < xs.length; i++) {
    const x = xs[i] - cx, y = ys[i] - cy;
    const r = Math.hypot(x, y);
    if (r < R * 0.30 || r > R * 0.95) continue;
    let a2 = Math.atan2(y, x); if (a2 < 0) a2 += Math.PI * 2;
    raw[Math.min(BINS - 1, (a2 / (Math.PI * 2) * BINS) | 0)] += 1;
  }
  for (let i = 0; i < BINS; i++) raw[i] = Math.sqrt(raw[i]);
  // Light circular boxcar so single-bin tessellation gaps don't read as spokes.
  const prof = new Float64Array(BINS);
  const K = 3;
  for (let i = 0; i < BINS; i++) {
    let s2 = 0;
    for (let k = -K; k <= K; k++) s2 += raw[(i + k + BINS) % BINS];
    prof[i] = s2 / (2 * K + 1);
  }
  const rep = repeatPeriodDeg(Array.from(prof));

  rows.push({ f, verts: pts.length, axle: 'xyz'[axle], R: R.toFixed(3), offPct: offPctFit, tiltDeg, rep });
}

const kmhAt = (deg, fps) => {
  // viewer: omega = kmh * 0.38 rad/s. safe step = 0.45 * period.
  const rad = deg * Math.PI / 180;
  return (0.45 * rad * fps) / 0.38;
};

console.log('file'.padEnd(34), 'axle', 'centre-off%', 'tilt°', 'repeat°', 'N', 'corr', ' alias@18fps(km/h)');
for (const r of rows) {
  if (r.err) { console.log(r.f.padEnd(34), 'ERR', r.err); continue; }
  console.log(
    r.f.padEnd(34),
    r.axle.padEnd(4),
    (isFinite(r.offPct) ? r.offPct.toFixed(2) : '  ? ').padStart(10),
    (isFinite(r.tiltDeg) ? r.tiltDeg.toFixed(2) : '  ? ').padStart(6),
    r.rep.deg.toFixed(1).padStart(7),
    String(r.rep.N).padStart(3),
    r.rep.corr.toFixed(2).padStart(5), (r.rep.weak ? '*' : ' '),
    kmhAt(r.rep.deg, 18).toFixed(1).padStart(8),
  );
}
