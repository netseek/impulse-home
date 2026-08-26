/**
 * Procedural Haval H6 hybrid powertrain — "ghost chassis" for the POWER widget.
 *
 * Builds the rolling chassis (subframes, suspension, wheels) plus the three
 * energy nodes the Fluxo/POWER card talks about — ICE, front e-motor (P2),
 * rear e-motor (P4) — the traction battery, and the orange HV cables that
 * connect them. Everything is generated from primitives, so there is no
 * texture, no DCC round-trip, and the whole thing re-bakes in ~2s.
 *
 * Every part is its own named glTF node with its own material, so the widget
 * can look a part up by name and drive emissive / visibility per power state.
 *
 * Coordinate convention (documented again in the .json sidecar):
 *   units  metres          +Y up
 *   -Z     front (nose)    +X right-hand side of the car
 * Origin sits on the ground plane, centred between the axles.
 *
 *   node scripts/build-powertrain-model.mjs [--lite] [--draco]
 */
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs/promises';
import * as THREE from 'three';
import { mergeGeometries, mergeVertices } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { Document, NodeIO, TextureInfo } from '@gltf-transform/core';
import fsSync from 'node:fs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ARGS = new Set(process.argv.slice(2));
const LITE = ARGS.has('--lite');
const DRACO = ARGS.has('--draco');

/* ---------------------------------------------------------------- geometry */

// Real H6 numbers where they matter (wheelbase, track, 225/55 R19), eyeballed
// where they do not. Tweak here, re-run, done.
const D = {
  wheelbase: 2.738,
  trackFront: 1.606,
  trackRear: 1.612,
  tireRadius: 0.3665,
  tireWidth: 0.212,
  rimRadius: 0.2680,
  axleF: -1.369,
  axleR: 1.369,
};
const hubXF = D.trackFront / 2;
const hubXR = D.trackRear / 2;

// Segment counts collapse in --lite so the MMI browser gets a cheaper mesh.
const S = LITE
  ? { tire: 26, rim: 14, motor: 14, cyl: 10, tube: 5 }
  : { tire: 28, rim: 22, motor: 24, cyl: 16, tube: 6 };

/** Normalise a piece so every geometry merges cleanly: indexed, no UVs. */
function prep(geo) {
  geo.deleteAttribute('uv');
  geo.deleteAttribute('uv1');
  return geo.index ? geo : mergeVertices(geo);
}

const box = (w, h, d) => prep(new THREE.BoxGeometry(w, h, d));

/** Chamfered box — reads as machined metal instead of a programmer cube. */
function chamferBox(w, h, d, c = 0.02) {
  const shape = new THREE.Shape();
  const x = w / 2 - c, z = d / 2 - c;
  shape.moveTo(-x, -z);
  shape.lineTo(x, -z); shape.lineTo(x + c, -z + c);
  shape.lineTo(x + c, z - c); shape.lineTo(x, z);
  shape.lineTo(-x, z); shape.lineTo(-x - c, z - c);
  shape.lineTo(-x - c, -z + c); shape.lineTo(-x, -z);
  const geo = new THREE.ExtrudeGeometry(shape, {
    depth: Math.max(0.001, h - c * 2), bevelEnabled: true,
    bevelThickness: c, bevelSize: c, bevelSegments: 1, steps: 1,
  });
  geo.rotateX(-Math.PI / 2);
  // Extrusion leaves the solid spanning [-c, h-c]; recentre it on the origin
  // so `at` means the same thing here as it does for box()/cylX().
  geo.translate(0, c - h / 2, 0);
  return prep(geo);
}

/** Cylinder with its axis along X (transverse) instead of three's default Y. */
function cylX(rTop, rBot, len, seg = S.cyl, open = false) {
  const geo = new THREE.CylinderGeometry(rTop, rBot, len, seg, 1, open);
  geo.rotateZ(Math.PI / 2);
  return prep(geo);
}
function cylY(rTop, rBot, len, seg = S.cyl) {
  return prep(new THREE.CylinderGeometry(rTop, rBot, len, seg, 1));
}
function cylZ(rTop, rBot, len, seg = S.cyl) {
  const geo = new THREE.CylinderGeometry(rTop, rBot, len, seg, 1);
  geo.rotateX(Math.PI / 2);
  return prep(geo);
}
const sphere = (r, seg = 8) => prep(new THREE.SphereGeometry(r, seg, Math.max(4, seg >> 1)));

/** Flat ring with thickness, around an X axis — wheel faces, spring seats. */
function annulusX(rIn, rOut, thickness, seg = S.rim) {
  const h = thickness / 2;
  const pts = [[rIn, -h], [rOut, -h], [rOut, h], [rIn, h], [rIn, -h]]
    .map(([r, a]) => new THREE.Vector2(r, a));
  const geo = new THREE.LatheGeometry(pts, seg);
  geo.rotateZ(Math.PI / 2);
  return prep(geo);
}

/** Ring around an X axis — motor cooling ribs, CV boots, spring seats. */
function ringX(radius, tube, seg = S.motor) {
  const geo = new THREE.TorusGeometry(radius, tube, 5, seg);
  geo.rotateY(Math.PI / 2);
  return prep(geo);
}

/** Swept tube through model-space points — HV cables, exhaust, intake. */
function tube(points, radius, radial = S.tube) {
  const curve = new THREE.CatmullRomCurve3(points.map((p) => new THREE.Vector3(...p)));
  const segs = Math.max(8, Math.round(curve.getLength() / (LITE ? 0.14 : 0.08)));
  return prep(new THREE.TubeGeometry(curve, segs, radius, radial, false));
}

/** Tyre as a lathed cross-section — proper sidewall bulge for ~380 tris. */
function tyre() {
  const R = D.tireRadius, w = D.tireWidth / 2, rim = D.rimRadius;
  // Straighter sidewall + flatter tread: the round-shouldered version read as
  // a toy tyre. Low profile over a big rim is what the reference shows.
  const pts = [
    [rim, -w], [R * 0.90, -w * 1.02], [R * 0.985, -w * 0.90], [R, -w * 0.70],
    [R, w * 0.70], [R * 0.985, w * 0.90], [R * 0.90, w * 1.02], [rim, w], [rim, -w],
  ].map(([r, a]) => new THREE.Vector2(r, a));
  const geo = new THREE.LatheGeometry(pts, S.tire);
  geo.rotateZ(Math.PI / 2);
  return prep(geo);
}

/* --------------------------------------------------------------- assembly */

// `tex` names a CC0 pack from build-powertrain-textures.mjs; `tile` is metres
// per texture repeat, which is what keeps texel density even across a 1.2 m
// battery lid and an 8 mm bolt head. Roughness comes from the map, so the
// roughness factor here is a multiplier, not an absolute.
// Colours are brighter than they look here, because a metal at 0.9 metalness
// has no diffuse term at all -- everything you see is reflected environment. An
// earlier pass used mid-greys (#6f7883 and darker) which read fine under the
// preview's overhead softboxes and went nearly black in the app's dimmer night
// rig. Real machined aluminium is genuinely bright, so these now sit where the
// metal actually is and stop depending on a flattering environment. Metalness
// came down too: engine covers and intakes are plastic, not mirror, and a
// lower value gives them a diffuse term the app's spotlights can actually
// light -- pure metal only responds to the environment map.
const MATERIALS = {
  frame:        { color: '#aeb5be', metallic: 0.82, roughness: 0.95, tex: 'machined', tile: 0.30 },
  frameDark:    { color: '#727a85', metallic: 0.55, roughness: 1.00, tex: 'trim',     tile: 0.26 },
  ice:          { color: '#b8bfc8', metallic: 0.42, roughness: 0.95, tex: 'machined', tile: 0.24 },
  iceTrim:      { color: '#8d949e', metallic: 0.30, roughness: 1.00, tex: 'trim',     tile: 0.20 },
  motor:        { color: '#bcc3cc', metallic: 0.66, roughness: 0.80, tex: 'machined', tile: 0.22 },
  batteryCase:  { color: '#c3c9d1', metallic: 0.42, roughness: 0.95, tex: 'machined', tile: 0.44, normalScale: 0.35 },
  batteryCell:  { color: '#7b838e', metallic: 0.40, roughness: 1.00, tex: 'cell',     tile: 0.16 },
  hv:           { color: '#ff7a2e', metallic: 0.05, roughness: 1.00, tex: 'trim',     tile: 0.10 },
  tyre:         { color: '#26292e', metallic: 0.00, roughness: 1.00, tex: 'rubber',   tile: 0.09, normalScale: 1.0 },
  rim:          { color: '#c2c8d1', metallic: 0.92, roughness: 0.62, tex: 'brushed',  tile: 0.20 },
  chrome:       { color: '#d8dee6', metallic: 0.96, roughness: 0.45, tex: 'brushed',  tile: 0.16 },
  disc:         { color: '#a9b0b9', metallic: 0.62, roughness: 0.85, tex: 'brushed',  tile: 0.14 },
};

const parts = [];
const groups = new Map();
const group = (name) => groups.set(name, true) && name;
/**
 * `pivot` is where the part's node sits; geometry is re-based onto it at
 * export. Without this every node would sit at the car's origin and
 * `wheel.rotation.x` would swing the wheel around the centreline instead of
 * spinning it. Defaults to the part's own bounding-box centre.
 */
function part(name, parentGroup, pivot = null) {
  const p = { name, parent: parentGroup, pieces: [], pivot };
  parts.push(p);
  return p;
}
/**
 * Box projection from world position, picking the plane the normal faces most.
 * Primitive UVs are useless here — three gives every box face 0..1 regardless
 * of size, so a bolt head and the battery lid would get the same texel density.
 * Seams appear where the dominant axis flips, which is invisible on tiling
 * roughness/normal detail.
 */
function projectUVs(geo, tile) {
  const pos = geo.attributes.position.array;
  const nor = geo.attributes.normal.array;
  const count = geo.attributes.position.count;
  const uv = new Float32Array(count * 2);
  for (let i = 0; i < count; i++) {
    const x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
    const nx = Math.abs(nor[i * 3]), ny = Math.abs(nor[i * 3 + 1]), nz = Math.abs(nor[i * 3 + 2]);
    let u, v;
    if (nx >= ny && nx >= nz) { u = z; v = y; }
    else if (ny >= nx && ny >= nz) { u = x; v = z; }
    else { u = x; v = y; }
    uv[i * 2] = u / tile;
    uv[i * 2 + 1] = v / tile;
  }
  geo.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
}

/** Bake a local transform into the piece and file it under a material. */
function add(p, geo, mat, { at = [0, 0, 0], rot = [0, 0, 0], scale } = {}) {
  const m = new THREE.Matrix4().compose(
    new THREE.Vector3(...at),
    new THREE.Quaternion().setFromEuler(new THREE.Euler(...rot)),
    new THREE.Vector3(...(scale || [1, 1, 1])),
  );
  geo.applyMatrix4(m);
  projectUVs(geo, mat.tile || 0.25);
  p.pieces.push({ geo, mat });
  return p;
}
/** Point a Y-up piece from a to b — struts, control arms, half-shafts. */
function between(a, b) {
  const va = new THREE.Vector3(...a), vb = new THREE.Vector3(...b);
  const dir = vb.clone().sub(va);
  const len = dir.length();
  const mid = va.clone().add(vb).multiplyScalar(0.5);
  const q = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir.normalize());
  const e = new THREE.Euler().setFromQuaternion(q);
  return { len, at: mid.toArray(), rot: [e.x, e.y, e.z] };
}
function strut(p, mat, a, b, r) {
  const { len, at, rot } = between(a, b);
  add(p, cylY(r, r, len, S.cyl), mat, { at, rot });
}
/** Tapered link (control arm, toe link), flattened so it reads as a stamping. */
function link(p, mat, a, b, r0, r1) {
  const { len, at, rot } = between(a, b);
  add(p, cylY(r1, r0, len, 6), mat, { at, rot, scale: [1, 1, 0.38] });
}

['Chassis', 'Suspension', 'Wheels', 'Powerplant_Front', 'Powerplant_Rear']
  .forEach(group);


/* ------------------------------------------------------------- donor rim */

/**
 * The repo already ships a modelled HEV rim, so there is no reason to fake one
 * from boxes. It arrives normalised to a unit diameter with its axle on Z, so
 * it gets rotated onto X and scaled to the real rim radius. Width is scaled
 * separately: a uniform scale would push the barrel wider than the tyre.
 */
const RIM_SRC = path.join(ROOT, 'assets', 'wheels', 'HavalHEV-wheel.glb');
let rimCache = null;

async function loadRim() {
  const { NodeIO } = await import('@gltf-transform/core');
  const [ext, draco] = await Promise.all([
    import('@gltf-transform/extensions'), import('draco3dgltf'),
  ]);
  const io = new NodeIO().registerExtensions(ext.ALL_EXTENSIONS).registerDependencies({
    'draco3d.decoder': await draco.createDecoderModule(),
  });
  const doc = await io.read(RIM_SRC);
  const scene = doc.getRoot().getDefaultScene() || doc.getRoot().listScenes()[0];

  const out = [];
  const walk = (node, parent) => {
    const local = new THREE.Matrix4().compose(
      new THREE.Vector3(...node.getTranslation()),
      new THREE.Quaternion(...node.getRotation()),
      new THREE.Vector3(...node.getScale()),
    );
    const world = parent.clone().multiply(local);
    const mesh = node.getMesh();
    if (mesh) {
      for (const prim of mesh.listPrimitives()) {
        const pos = prim.getAttribute('POSITION');
        const nor = prim.getAttribute('NORMAL');
        const idx = prim.getIndices();
        if (!pos) continue;
        const geo = new THREE.BufferGeometry();
        geo.setAttribute('position', new THREE.BufferAttribute(pos.getArray().slice(), 3));
        if (nor) geo.setAttribute('normal', new THREE.BufferAttribute(nor.getArray().slice(), 3));
        if (idx) geo.setIndex(new THREE.BufferAttribute(idx.getArray().slice(), 1));
        if (!nor) geo.computeVertexNormals();
        geo.applyMatrix4(world);
        out.push(geo);
      }
    }
    for (const child of node.listChildren()) walk(child, world);
  };
  for (const node of scene.listChildren()) walk(node, new THREE.Matrix4());

  // Normalise: measure, centre, orient axle onto X, scale to the real rim.
  const merged = mergeVertices(mergeGeometries(out.map((g) => prep(g))));
  merged.computeBoundingBox();
  const bb = merged.boundingBox;
  const size = new THREE.Vector3().subVectors(bb.max, bb.min);
  const centre = new THREE.Vector3().addVectors(bb.min, bb.max).multiplyScalar(0.5);
  merged.translate(-centre.x, -centre.y, -centre.z);
  merged.rotateY(Math.PI / 2);                        // axle Z -> X, face outboard
  const diameter = Math.max(size.x, size.y);          // in-plane extent
  const radial = (D.rimRadius * 2) / diameter;
  const axial = (D.tireWidth * 0.88) / size.z;
  merged.scale(axial, radial, radial);

  if (LITE) {
    const { MeshoptSimplifier } = await import('meshoptimizer');
    await MeshoptSimplifier.ready;
    // simplifySloppy, not simplify: the donor rim is several open shells with
    // non-manifold seams, and topology-preserving collapse stalls at ~75% no
    // matter how loose the error budget. Sloppy ignores topology, which is fine
    // for a part this small on screen.
    // simplifySloppy asserts unless it gets Uint32 indices and Float32 positions.
    const src = new Uint32Array(merged.index.array);
    const pos = new Float32Array(merged.attributes.position.array);
    const target = Math.floor((src.length / 3) * 0.30) * 3;
    // Note the arg order: vertex_lock sits before target_index_count here.
    const [indices, error] = MeshoptSimplifier.simplifySloppy(src, pos, 3, null, target, 0.25);
    merged.setIndex(new THREE.BufferAttribute(indices, 1));
    console.log(`  rim simplified to ${(indices.length / 3) | 0} tris (error ${error.toFixed(3)})`);
  }
  rimCache = merged;
}

/**
 * Fresh copy per wheel — add() bakes transforms, so they cannot be shared.
 * The left side is mirrored so both faces point outboard; mirroring inverts
 * winding, so the index order is reversed to match.
 */
function rimGeometries(sx) {
  const g = rimCache.clone();
  if (sx < 0) {
    g.scale(-1, 1, 1);
    const idx = g.index.array;
    for (let i = 0; i < idx.length; i += 3) {
      const t = idx[i]; idx[i] = idx[i + 2]; idx[i + 2] = t;
    }
    g.index.needsUpdate = true;
  }
  return [g];
}

await loadRim();

/* --- structure: skateboard sills + cast subframes ------------------------ */
// The first pass hung the pack off two floating tie-bars, which read as sticks.
// A PHEV floorpan is really a pair of box-section sills running the length of
// the pack and landing on both cradles, so that is what this builds: the pack
// sits inside a structure instead of beside one.
// No perimeter frame and no tunnel. This is a unibody: on the pack-in-floor
// variants the battery tray is the structure between the axles (built with the
// pack, below), and everything else lives on the two cradles. The car-length
// rectangle that used to sit here was a ladder chassis, which this car does not
// have; the segmented spine that replaced it read as a row of loose boxes
// rather than a channel, so it is gone too.

for (const [name, z0, z1, hx] of [
  ['Subframe_Front', -1.80, -1.05, 0.60],
  ['Subframe_Rear', 1.06, 1.78, 0.58],
]) {
  // Cast cradle: beams swell toward the bushing towers and carry rib detail,
  // rather than being four sticks of constant section.
  const p = part(name, 'Chassis');
  add(p, chamferBox(hx * 2 + 0.08, 0.062, 0.075, 0.012), MATERIALS.frame, { at: [0, 0.272, z0] });
  add(p, chamferBox(hx * 2 - 0.06, 0.068, 0.085, 0.012), MATERIALS.frame, { at: [0, 0.272, z1] });
  for (const sx of [-1, 1]) {
    const mid = (z0 + z1) / 2;
    add(p, chamferBox(0.070, 0.060, z1 - z0, 0.012), MATERIALS.frame, { at: [sx * hx, 0.272, mid] });
    // Swellings at the load paths.
    add(p, chamferBox(0.105, 0.085, 0.20, 0.020), MATERIALS.frame, { at: [sx * hx, 0.272, z0 + (z1 - z0) * 0.18] });
    add(p, chamferBox(0.105, 0.085, 0.20, 0.020), MATERIALS.frame, { at: [sx * hx, 0.272, z0 + (z1 - z0) * 0.82] });
    // Cast ribs across the top face.
    for (let i = 0; i < 3; i++) {
      add(p, box(0.055, 0.016, 0.10), MATERIALS.frame,
          { at: [sx * hx, 0.305, z0 + (z1 - z0) * (0.34 + i * 0.16)] });
    }
    add(p, cylY(0.024, 0.024, 0.12, 8), MATERIALS.frame, { at: [sx * hx, 0.345, z0 + (z1 - z0) * 0.18] });
    add(p, cylY(0.030, 0.030, 0.042, 8), MATERIALS.frameDark, { at: [sx * hx, 0.410, z0 + (z1 - z0) * 0.18] });
    add(p, cylY(0.024, 0.024, 0.12, 8), MATERIALS.frame, { at: [sx * hx, 0.345, z0 + (z1 - z0) * 0.82] });
    add(p, cylY(0.030, 0.030, 0.042, 8), MATERIALS.frameDark, { at: [sx * hx, 0.410, z0 + (z1 - z0) * 0.82] });
  }
  // Diagonal brace, the detail that makes a cradle read as cast not welded.
  for (const sx of [-1, 1]) {
    link(p, MATERIALS.frame, [sx * (hx - 0.06), 0.272, z0 + 0.10], [sx * 0.10, 0.272, (z0 + z1) / 2], 0.030, 0.020);
  }
}
{
  const p = part('Steering_Rack', 'Chassis');
  add(p, cylX(0.030, 0.030, 1.02), MATERIALS.frameDark, { at: [0, 0.40, -1.20] });
  add(p, chamferBox(0.18, 0.080, 0.095, 0.012), MATERIALS.frame, { at: [-0.16, 0.40, -1.20] });
  for (const sx of [-1, 1]) strut(p, MATERIALS.chrome, [sx * 0.50, 0.40, -1.20], [sx * 0.70, 0.40, -1.31], 0.012);
}

/* --- suspension + wheels ------------------------------------------------- */
for (const [tag, sx, z, hx, front] of [
  ['FL', -1, D.axleF, hubXF, true], ['FR', 1, D.axleF, hubXF, true],
  ['RL', -1, D.axleR, hubXR, false], ['RR', 1, D.axleR, hubXR, false],
]) {
  const p = part(`Susp_${tag}`, 'Suspension');
  const hub = [sx * (hx - 0.055), D.tireRadius, z];
  const inner = sx * 0.42;

  // Upright + vented disc + caliper.
  add(p, chamferBox(0.062, 0.26, 0.095, 0.010), MATERIALS.frameDark,
      { at: [sx * (hx - 0.10), D.tireRadius + 0.04, z] });
  add(p, cylX(0.170, 0.170, 0.020, S.rim), MATERIALS.disc, { at: hub });
  add(p, annulusX(0.070, 0.168, 0.030), MATERIALS.disc,
      { at: [hub[0] - sx * 0.014, hub[1], hub[2]] });
  add(p, cylX(0.052, 0.052, 0.075, 8), MATERIALS.chrome, { at: hub });
  add(p, chamferBox(0.052, 0.11, 0.075, 0.008), MATERIALS.frameDark,
      { at: [sx * (hx - 0.105), D.tireRadius + 0.135, z + 0.045] });

  // Lower arm(s): an A-arm up front, a multi-link out back.
  if (front) {
    link(p, MATERIALS.frame, [inner, 0.27, z - 0.20], [sx * (hx - 0.09), 0.30, z], 0.030, 0.017);
    link(p, MATERIALS.frame, [inner, 0.27, z + 0.26], [sx * (hx - 0.09), 0.30, z], 0.030, 0.017);
  } else {
    // Multi-link rear, which is what the H6 actually runs (MacPherson front,
    // multi-link rear). Five members per side, each doing one job:
    const knuckleLo = [sx * (hx - 0.095), 0.300, z];
    const knuckleUp = [sx * (hx - 0.115), 0.520, z - 0.010];

    //  1. lower control arm — broad, transverse, carries the spring seat
    link(p, MATERIALS.frame, [inner - sx * 0.02, 0.278, z + 0.02], knuckleLo, 0.046, 0.022);
    add(p, chamferBox(0.16, 0.020, 0.115, 0.010), MATERIALS.frame,
        { at: [sx * (hx - 0.30), 0.286, z + 0.015] });
    //  2. trailing arm — runs forward to a body mount ahead of the axle
    link(p, MATERIALS.frame, [sx * (hx - 0.16), 0.292, z - 0.46], knuckleLo, 0.020, 0.030);
    add(p, chamferBox(0.075, 0.055, 0.055, 0.010), MATERIALS.frameDark,
        { at: [sx * (hx - 0.16), 0.292, z - 0.475] });
    //  3. upper camber link — transverse, high, ahead of centre
    link(p, MATERIALS.frameDark, [inner + sx * 0.05, 0.512, z - 0.085], knuckleUp, 0.019, 0.013);
    //  4. toe link — transverse, behind the axle line, sets rear steer
    link(p, MATERIALS.frame, [inner + sx * 0.07, 0.318, z + 0.215],
         [sx * (hx - 0.105), 0.330, z + 0.135], 0.019, 0.013);
    //  5. second lower link, closing the wide-base lower wishbone
    link(p, MATERIALS.frame, [inner + sx * 0.02, 0.286, z + 0.245],
         [sx * (hx - 0.095), 0.302, z + 0.030], 0.026, 0.016);

    // Inboard pivot bushings on the subframe.
    for (const [ix, iz] of [[inner - sx * 0.02, z + 0.02], [inner + sx * 0.05, z - 0.085],
                            [inner + sx * 0.07, z + 0.215], [inner + sx * 0.02, z + 0.245]]) {
      add(p, cylX(0.026, 0.026, 0.055, 8), MATERIALS.frameDark, { at: [ix, iz === z + 0.02 ? 0.278 : 0.31, iz] });
    }
  }

  // No coil-overs: they sit exactly where the body's inner arches are, so they
  // fight the car mesh once this is overlaid on it. Only the parts that carry
  // the power story survive — uprights, arms, hubs, discs.

  // Wheel: the project's own HEV rim, with the procedural tyre wrapped round it.
  // Pivot pinned to the hub so Wheel_*.rotation.x spins in place.
  const R = D.rimRadius;
  const centre = [sx * hx, D.tireRadius, z];
  const w = part(`Wheel_${tag}`, 'Wheels', centre);
  add(w, tyre(), MATERIALS.tyre, { at: centre });
  for (const geo of rimGeometries(sx)) {
    add(w, geo, MATERIALS.rim, { at: centre });
  }
}

/* --- ICE: transverse 1.5T, offset to the right of the DHT ---------------- */
{
  const p = part('ICE', 'Powerplant_Front');
  const cx = 0.20, cy = 0.53, cz = -1.44;
  add(p, chamferBox(0.42, 0.30, 0.44, 0.022), MATERIALS.ice, { at: [cx, cy, cz] });
  add(p, chamferBox(0.40, 0.11, 0.40, 0.016), MATERIALS.ice, { at: [cx, cy + 0.20, cz] });
  add(p, chamferBox(0.36, 0.10, 0.28, 0.020), MATERIALS.iceTrim, { at: [cx, cy + 0.30, cz - 0.03] });
  // Cam-cover ribs — cheap, but they stop the head reading as a blank slab.
  for (let i = 0; i < 4; i++) {
    add(p, box(0.30, 0.022, 0.024), MATERIALS.ice, { at: [cx, cy + 0.35, cz - 0.13 + i * 0.066] });
  }
  add(p, chamferBox(0.32, 0.13, 0.34, 0.030), MATERIALS.iceTrim, { at: [cx, cy - 0.21, cz] });
  // Intake plenum + runners arcing over the head, rear-facing.
  add(p, cylX(0.062, 0.062, 0.34, 10), MATERIALS.iceTrim, { at: [cx, cy + 0.30, cz + 0.26] });
  for (let i = 0; i < 4; i++) {
    const x = cx - 0.135 + i * 0.09;
    add(p, tube([[x, cy + 0.30, cz + 0.24], [x, cy + 0.36, cz + 0.14], [x, cy + 0.28, cz + 0.05]], 0.027, 5),
        MATERIALS.iceTrim);
  }
  // Exhaust manifold: four runners collecting into the turbo, front-facing.
  for (let i = 0; i < 4; i++) {
    const x = cx - 0.135 + i * 0.09;
    add(p, tube([[x, cy + 0.14, cz - 0.21], [x, cy + 0.08, cz - 0.28],
                 [cx - 0.02, cy + 0.02, cz - 0.31]], 0.024, 5), MATERIALS.chrome);
  }
  add(p, cylX(0.062, 0.062, 0.09, 10), MATERIALS.chrome, { at: [cx - 0.03, cy + 0.00, cz - 0.33] });
  add(p, ringX(0.066, 0.032, 10), MATERIALS.chrome, { at: [cx + 0.04, cy + 0.00, cz - 0.33] });
  // Accessory drive on the outboard end.
  add(p, cylX(0.090, 0.090, 0.045, 12), MATERIALS.iceTrim, { at: [cx + 0.235, cy + 0.02, cz] });
  add(p, cylX(0.052, 0.052, 0.035, 10), MATERIALS.chrome, { at: [cx + 0.272, cy + 0.02, cz] });
  add(p, cylX(0.046, 0.046, 0.040, 10), MATERIALS.iceTrim, { at: [cx + 0.245, cy + 0.17, cz - 0.06] });
  // Oil filter + starter bulge, so the block face is not empty.
  add(p, cylZ(0.042, 0.042, 0.095, 10), MATERIALS.iceTrim, { at: [cx + 0.10, cy - 0.12, cz + 0.26] });
  // Charge-air pipe from the turbo, up and over to the intake — the single
  // biggest piece of visible plumbing on a real transverse 1.5T.
  add(p, tube([[cx - 0.06, cy - 0.02, cz - 0.36], [cx + 0.24, cy + 0.02, cz - 0.34],
               [cx + 0.30, cy + 0.26, cz - 0.10], [cx + 0.20, cy + 0.33, cz + 0.16],
               [cx + 0.05, cy + 0.32, cz + 0.24]], 0.030), MATERIALS.chrome);
  // Coolant hoses.
  add(p, tube([[cx - 0.19, cy + 0.16, cz + 0.20], [cx - 0.34, cy + 0.10, cz + 0.14],
               [cx - 0.40, cy - 0.02, cz - 0.02]], 0.021, 5), MATERIALS.iceTrim);
  add(p, tube([[cx - 0.19, cy - 0.06, cz - 0.16], [cx - 0.36, cy - 0.10, cz - 0.20],
               [cx - 0.44, cy - 0.14, cz - 0.10]], 0.018, 5), MATERIALS.iceTrim);
  // A/C compressor slung off the front of the block.
  add(p, cylX(0.058, 0.058, 0.13, 10), MATERIALS.iceTrim, { at: [cx + 0.14, cy - 0.16, cz - 0.24] });
  add(p, cylX(0.044, 0.044, 0.030, 10), MATERIALS.chrome, { at: [cx + 0.215, cy - 0.16, cz - 0.24] });
  // Engine mounts tying the block to the cradle.
  add(p, chamferBox(0.10, 0.055, 0.085, 0.010), MATERIALS.frame, { at: [cx + 0.20, cy + 0.13, cz + 0.06] });
  add(p, cylY(0.030, 0.030, 0.11, 8), MATERIALS.frameDark, { at: [cx + 0.24, cy + 0.05, cz + 0.06] });
}

/* --- DHT: front drive motor (P2) + integrated generator ------------------ */
{
  const p = part('Motor_P2', 'Powerplant_Front');
  const mx = -0.30, my = 0.53, mz = -1.42;
  add(p, cylX(0.185, 0.185, 0.30, S.motor), MATERIALS.motor, { at: [mx, my, mz] });
  add(p, cylX(0.185, 0.215, 0.10, S.motor), MATERIALS.motor, { at: [mx + 0.20, my, mz] });
  add(p, cylX(0.125, 0.125, 0.06, 12), MATERIALS.iceTrim, { at: [mx - 0.18, my, mz] });
  for (let i = 0; i < 5; i++) {
    add(p, ringX(0.192, 0.011, S.motor), MATERIALS.motor, { at: [mx - 0.12 + i * 0.06, my, mz] });
  }
  // P1 generator stacked above the transmission case.
  add(p, cylX(0.100, 0.100, 0.20, 12), MATERIALS.motor, { at: [mx - 0.02, my + 0.24, mz - 0.10] });
  add(p, cylX(0.078, 0.078, 0.04, 10), MATERIALS.iceTrim, { at: [mx - 0.14, my + 0.24, mz - 0.10] });
  add(p, chamferBox(0.16, 0.20, 0.22, 0.015), MATERIALS.motor, { at: [mx - 0.02, my - 0.16, mz + 0.14] });
  // Orange three-phase terminal box, so the unit reads as electric on sight.
  add(p, chamferBox(0.13, 0.055, 0.12, 0.010), MATERIALS.hv, { at: [mx, my + 0.20, mz + 0.11] });
}
{
  const p = part('Halfshaft_Front', 'Powerplant_Front');
  for (const [sx, inner] of [[-1, -0.20], [1, 0.10]]) {
    const hub = [sx * (hubXF - 0.10), D.tireRadius, D.axleF];
    strut(p, MATERIALS.chrome, [inner, 0.44, D.axleF], hub, 0.0165);
    add(p, sphere(0.038, 8), MATERIALS.frameDark, { at: hub });
    add(p, cylX(0.044, 0.028, 0.085, 8), MATERIALS.frameDark, { at: [inner + sx * 0.05, 0.44, D.axleF] });
  }
}

/* --- rear drive unit (P4) ------------------------------------------------ */
{
  const p = part('Motor_P4', 'Powerplant_Rear');
  const mx = -0.06, my = 0.42, mz = 1.42;
  add(p, cylX(0.155, 0.155, 0.34, S.motor), MATERIALS.motor, { at: [mx, my, mz] });
  add(p, cylX(0.155, 0.180, 0.08, S.motor), MATERIALS.motor, { at: [mx + 0.21, my, mz] });
  for (let i = 0; i < 5; i++) {
    add(p, ringX(0.162, 0.010, S.motor), MATERIALS.motor, { at: [mx - 0.13 + i * 0.065, my, mz] });
  }
  add(p, chamferBox(0.20, 0.26, 0.24, 0.018), MATERIALS.motor, { at: [mx + 0.30, my - 0.02, mz] });
  add(p, cylX(0.090, 0.090, 0.05, 12), MATERIALS.iceTrim, { at: [mx - 0.19, my, mz] });
  add(p, chamferBox(0.12, 0.050, 0.11, 0.010), MATERIALS.hv, { at: [mx, my + 0.14, mz - 0.09] });
}
{
  const p = part('Halfshaft_Rear', 'Powerplant_Rear');
  for (const [sx, inner] of [[-1, -0.24], [1, 0.16]]) {
    const hub = [sx * (hubXR - 0.10), D.tireRadius, D.axleR];
    strut(p, MATERIALS.chrome, [inner, 0.42, D.axleR], hub, 0.0155);
    add(p, sphere(0.036, 8), MATERIALS.frameDark, { at: hub });
    add(p, cylX(0.042, 0.026, 0.080, 8), MATERIALS.frameDark, { at: [inner + sx * 0.05, 0.42, D.axleR] });
  }
}

/* --- traction battery: three selectable packs ---------------------------- */
// All three ship in one GLB as sibling groups so the widget can switch drivetrain
// without another download. Each owns its own case, lid, modules, junction and
// HV runs, because the cable routing genuinely differs between them.
// Pack heights are set so the tray underside clears the ground by ~175 mm,
// which is where a real floor pack sits -- tucked up into the floorpan rather
// than hanging below the sills. Measured in-app against the wheel contact
// patch, not guessed.
// `awd` is the important one: only the big PHEV drives the rear axle. The other
// two are front-drive, so they get no rear motor, no rear half-shafts and no
// rear HV run at all — see the Powerplant_Rear handling below.
const PACKS = {
  phev34: {
    label: 'PHEV 34 kWh', kwh: 34, modules: 6, awd: true,
    x: 0, y: 0.320, z: -0.02, w: 1.26, d: 1.52, h: 0.115,
  },
  phev19: {
    label: 'PHEV 19 kWh', kwh: 19, modules: 5, awd: false,
    x: 0, y: 0.315, z: 0.06, w: 1.16, d: 1.10, h: 0.100,
  },
  hev: {
    // A flat under-floor slab rather than the chunky box the fuel tank was,
    // sitting under the rear seat ahead of the (undriven) rear axle.
    label: 'HEV 2 kWh', kwh: 2, modules: 3, awd: false,
    x: 0, y: 0.345, z: 0.86, w: 0.78, d: 0.40, h: 0.105,
  },
};

const packMeta = {};
for (const [key, B] of Object.entries(PACKS)) {
  const G = 'Battery_' + key.toUpperCase();
  group(G);
  const P = key.toUpperCase();
  const spine = Math.min(0.36, B.w * 0.30);
  const shoulder = (B.w - spine) / 2 - 0.03;

  const cs = part(`${P}_Case`, G);
  add(cs, chamferBox(B.w, B.h, B.d, 0.014), MATERIALS.batteryCase,
      { at: [B.x, B.y - B.h / 2, B.z] });
  add(cs, chamferBox(B.w - 0.05, 0.020, B.d - 0.05, 0.008), MATERIALS.frameDark,
      { at: [B.x, B.y - B.h - 0.009, B.z] });
  add(cs, box(B.w - 0.16, 0.012, B.d - 0.16), MATERIALS.frame,
      { at: [B.x, B.y - B.h - 0.024, B.z] });
  // Module ends along the front and rear faces.
  const ends = Math.max(3, Math.round(B.w / 0.24));
  for (const sz of [-1, 1]) for (let i = 0; i < ends; i++) {
    const step = (B.w - 0.20) / (ends - 1);
    add(cs, box(step * 0.55, 0.030, 0.020), MATERIALS.frameDark,
        { at: [B.x - (B.w - 0.20) / 2 + i * step, B.y - B.h * 0.55, B.z + sz * (B.d / 2 + 0.006)] });
  }
  // The tray is the structure: cross rails close the ends and horns reach
  // fore/aft onto the cradles. No side rails — the pack's own case walls do
  // that job, and drawn as separate bars they just read as clutter.
  for (const sz of [-1, 1]) {
    add(cs, chamferBox(B.w + 0.10, B.h * 0.55, 0.055, 0.010), MATERIALS.frame,
        { at: [B.x, B.y - B.h / 2, B.z + sz * (B.d / 2 + 0.026)] });
  }
  // Horns to the cradles: forward always, rearward only far enough to reach.
  for (const sx of [-1, 1]) {
    link(cs, MATERIALS.frame,
      [sx * (B.w / 2 + 0.02), B.y - B.h / 2, B.z - B.d / 2],
      [sx * 0.56, 0.285, -1.30], 0.034, 0.022);
    link(cs, MATERIALS.frame,
      [sx * (B.w / 2 + 0.02), B.y - B.h / 2, B.z + B.d / 2],
      [sx * 0.54, 0.285, 1.24], 0.032, 0.021);
  }

  const lid = part(`${P}_Lid`, G);
  add(lid, chamferBox(B.w - 0.024, 0.016, B.d - 0.024, 0.008), MATERIALS.batteryCase,
      { at: [B.x, B.y + 0.008, B.z] });
  for (const sx of [-1, 1]) {
    add(lid, chamferBox(shoulder, 0.024, B.d - 0.07, 0.008), MATERIALS.batteryCase,
        { at: [B.x + sx * (spine / 2 + shoulder / 2 + 0.015), B.y + 0.024, B.z] });
  }
  add(lid, box(spine + 0.06, 0.010, B.d - 0.055), MATERIALS.frameDark,
      { at: [B.x, B.y + 0.018, B.z] });

  // Modules: internal block + a gauge segment in the lid spine + flank strips.
  const span = B.d - Math.min(0.14, B.d * 0.18);
  const seg = span / B.modules;
  const moduleNames = [];
  for (let i = 0; i < B.modules; i++) {
    const name = `${P}_Module_${i + 1}`;
    moduleNames.push(name);
    const mp = part(name, G);
    const z = B.z - span / 2 + seg * (i + 0.5);
    add(mp, chamferBox(B.w - 0.11, B.h * 0.62, seg - Math.min(0.020, seg * 0.16), 0.006),
        MATERIALS.batteryCell, { at: [B.x, B.y - B.h * 0.50, z] });
    add(mp, chamferBox(spine, 0.018, seg - Math.min(0.055, seg * 0.30), 0.005),
        MATERIALS.batteryCell, { at: [B.x, B.y + 0.022, z] });
    for (const sx of [-1, 1]) {
      add(mp, box(0.012, Math.min(0.032, B.h * 0.38), seg - Math.min(0.035, seg * 0.22)),
          MATERIALS.batteryCell, { at: [sx * (B.w / 2 + 0.003), B.y - B.h * 0.60, z] });
    }
  }

  // HV: junction on the pack, then a run to each drive unit.
  const jz = key === 'hev' ? B.z - B.d / 2 - 0.02 : B.z - B.d / 2 + 0.02;
  const jn = part(`${P}_HV_Junction`, G);
  add(jn, chamferBox(0.20, 0.062, 0.135, 0.010), MATERIALS.batteryCase,
      { at: [-0.02, B.y + 0.037, jz] });
  add(jn, chamferBox(0.070, 0.040, 0.055, 0.006), MATERIALS.hv,
      { at: [-0.10, B.y + 0.080, jz] });

  const front = [
    [-0.10, B.y + 0.067, jz],
    [-0.20, 0.340, -0.92], [-0.28, 0.430, -1.14],
    [-0.30, 0.620, -1.24], [-0.30, 0.730, -1.31],
  ];
  // Front-drive packs have nothing at the back to feed, so no rear cable.
  const rear = B.awd
    ? [[0.08, B.y + 0.037, B.z + B.d / 2 - 0.04],
       [0.12, 0.320, 1.00], [0.02, 0.380, 1.22], [-0.06, 0.500, 1.34]]
    : null;

  const runs = [['Front', front]];
  if (rear) runs.push(['Rear', rear]);
  for (const [suffix, pts] of runs) {
    const cp = part(`${P}_HV_Cable_${suffix}`, G);
    for (const off of [-0.020, 0.020]) {
      add(cp, tube(pts.map(([x, y, z]) => [x + off, y, z]), 0.0125), MATERIALS.hv);
    }
  }

  packMeta[key] = {
    label: B.label, kwh: B.kwh, group: G,
    // Front-drive packs must hide Powerplant_Rear (motor + half-shafts) and
    // cannot reach the P4 / AWD / rear-regen states.
    awd: !!B.awd,
    drive: B.awd ? 'AWD (P2 + P4)' : 'FWD (P2)',
    dims: { w: B.w, h: B.h, d: B.d }, position: [B.x, B.y, B.z],
    nodes: {
      case: `${P}_Case`, lid: `${P}_Lid`, junction: `${P}_HV_Junction`,
      hvFront: `${P}_HV_Cable_Front`,
      hvRear: B.awd ? `${P}_HV_Cable_Rear` : null,
    },
    hidesGroups: B.awd ? [] : ['Powerplant_Rear'],
    modules: moduleNames,
    paths: { hvFront: front, hvRear: rear },
  };
}

/* --- exhaust ------------------------------------------------------------- */
// The fuel tank that used to sit between the pack and the rear motor is gone;
// the HEV pack occupies that space instead.
{
  const p = part('Exhaust', 'Chassis');
  add(p, tube([
    [0.13, 0.44, -1.72], [0.16, 0.34, -1.40], [0.30, 0.26, -1.00],
    [0.56, 0.23, -0.30], [0.58, 0.23, 0.55], [0.50, 0.26, 1.15],
  ], 0.024), MATERIALS.chrome);
  add(p, chamferBox(0.44, 0.105, 0.24, 0.045), MATERIALS.chrome, { at: [0.42, 0.265, 1.40] });
  add(p, tube([[0.42, 0.28, 1.53], [0.44, 0.30, 1.74]], 0.021, 5), MATERIALS.chrome);
}

/* ------------------------------------------------------------------ export */

const srgbToLinear = (c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
function linearRGBA(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
    .map((v) => srgbToLinear(v / 255))
    .concat(1);
}

const doc = new Document();
const buffer = doc.createBuffer();
const scene = doc.createScene('H6_Powertrain');
doc.getRoot().setDefaultScene(scene);

const TEX_DIR = path.join(ROOT, 'assets', 'power', 'tex');
const TEX_SUFFIX = LITE ? '@256' : '';
const texCache = new Map();
function texture(set, kind) {
  const file = `${set}_${kind}${TEX_SUFFIX}.jpg`;
  if (!texCache.has(file)) {
    const abs = path.join(TEX_DIR, file);
    if (!fsSync.existsSync(abs)) {
      throw new Error(`missing ${file} — run: npm run build:powertrain-textures`);
    }
    texCache.set(file, doc.createTexture(file)
      .setImage(fsSync.readFileSync(abs)).setMimeType('image/jpeg'));
  }
  return texCache.get(file);
}
const repeat = (info) => info
  .setWrapS(TextureInfo.WRAP_REPEAT).setWrapT(TextureInfo.WRAP_REPEAT);

const matKeyOf = new Map(Object.entries(MATERIALS).map(([k, v]) => [v, k]));
const matCache = new Map();
function material(spec) {
  const key = matKeyOf.get(spec);
  if (!matCache.has(key)) {
    matCache.set(key, doc.createMaterial(key)
      .setBaseColorFactor(linearRGBA(spec.color))
      .setMetallicFactor(spec.metallic)
      .setRoughnessFactor(spec.roughness)
      // Present-but-black so three.js compiles the emissive uniform and the
      // widget can just raise material.emissive at runtime.
      .setEmissiveFactor([0, 0, 0]));
    const mat = matCache.get(key);
    if (spec.tex) {
      mat.setMetallicRoughnessTexture(texture(spec.tex, 'mr'));
      repeat(mat.getMetallicRoughnessTextureInfo());
      mat.setNormalTexture(texture(spec.tex, 'n'));
      mat.setNormalScale(spec.normalScale ?? 0.55);
      repeat(mat.getNormalTextureInfo());
    }
  }
  return matCache.get(key);
}

const accessor = (name, array, type) =>
  doc.createAccessor(name).setArray(array).setType(type).setBuffer(buffer);

const groupNodes = new Map();
for (const name of groups.keys()) {
  const n = doc.createNode(name);
  scene.addChild(n);
  groupNodes.set(name, n);
}

let totalTris = 0, totalVerts = 0;
const pivots = {};
for (const p of parts) {
  // Re-base the part onto its pivot so the node carries the placement and the
  // geometry is local. Rotating/scaling a node now does the expected thing.
  const bounds = new THREE.Box3();
  for (const { geo } of p.pieces) {
    geo.computeBoundingBox();
    bounds.union(geo.boundingBox);
  }
  const pivot = p.pivot || bounds.getCenter(new THREE.Vector3()).toArray();
  pivots[p.name] = pivot.map((v) => +v.toFixed(4));
  for (const { geo } of p.pieces) geo.translate(-pivot[0], -pivot[1], -pivot[2]);

  const byMat = new Map();
  for (const { geo, mat } of p.pieces) {
    if (!byMat.has(mat)) byMat.set(mat, []);
    byMat.get(mat).push(geo);
  }
  const mesh = doc.createMesh(p.name);
  for (const [mat, geos] of byMat) {
    // Weld after merging: the primitives share corners at every seam, and
    // mergeVertices only collapses vertices whose normals match too, so hard
    // edges survive. Worth ~35% of the buffer.
    const merged = mergeVertices(geos.length === 1 ? geos[0] : mergeGeometries(geos));
    const count = merged.attributes.position.count;
    const idx = merged.index.array;
    totalVerts += count;
    totalTris += idx.length / 3;
    mesh.addPrimitive(doc.createPrimitive()
      .setAttribute('POSITION', accessor(`${p.name}_P`, new Float32Array(merged.attributes.position.array), 'VEC3'))
      .setAttribute('NORMAL', accessor(`${p.name}_N`, new Float32Array(merged.attributes.normal.array), 'VEC3'))
      .setAttribute('TEXCOORD_0', accessor(`${p.name}_UV`, new Float32Array(merged.attributes.uv.array), 'VEC2'))
      .setIndices(accessor(`${p.name}_I`, count > 65535 ? new Uint32Array(idx) : new Uint16Array(idx), 'SCALAR'))
      .setMaterial(material(mat)));
  }
  groupNodes.get(p.parent).addChild(
    doc.createNode(p.name).setMesh(mesh).setTranslation(pivot));
}

const io = new NodeIO();
if (DRACO) {
  const [{ KHRDracoMeshCompression }, draco3d] = await Promise.all([
    import('@gltf-transform/extensions'), import('draco3dgltf'),
  ]);
  io.registerExtensions([KHRDracoMeshCompression]).registerDependencies({
    'draco3d.encoder': await draco3d.createEncoderModule(),
  });
  doc.createExtension(KHRDracoMeshCompression).setRequired(true)
    .setEncoderOptions({ method: KHRDracoMeshCompression.EncoderMethod.EDGEBREAKER });
}

const suffix = LITE ? '-lite' : '';
const outGlb = path.join(ROOT, 'assets', 'power', `haval-powertrain${suffix}.glb`);
await io.write(outGlb, doc);

/* Sidecar: everything the widget needs that is not geometry. */
const manifest = {
  generatedBy: 'scripts/build-powertrain-model.mjs',
  tier: LITE ? 'lite' : 'full',
  units: 'meters',
  convention: { up: '+Y', forward: '-Z', right: '+X', origin: 'ground plane, centred between axles' },
  dims: D,
  stats: { triangles: totalTris, vertices: totalVerts, parts: parts.length },
  // Each node's local origin. Wheels pivot on the hub axis, so setting
  // Wheel_*.rotation.x spins them in place.
  pivots,
  nodes: {
    ice: 'ICE', motorFront: 'Motor_P2', motorRear: 'Motor_P4',
    wheels: ['Wheel_FL', 'Wheel_FR', 'Wheel_RL', 'Wheel_RR'],
    groups: [...groups.keys()],
  },
  // Three packs ship in one file; show one group, hide the others.
  batteries: packMeta,
  defaultBattery: 'phev34',
  materials: Object.fromEntries(Object.entries(MATERIALS).map(([k, v]) => [k, v.color])),
  anchors: {
    ice: [0.13, 0.56, -1.44],
    motorFront: [-0.30, 0.53, -1.42],
    motorRear: [-0.06, 0.42, 1.42],
    hubFL: [-hubXF, D.tireRadius, D.axleF], hubFR: [hubXF, D.tireRadius, D.axleF],
    hubRL: [-hubXR, D.tireRadius, D.axleR], hubRR: [hubXR, D.tireRadius, D.axleR],
  },
  // Half-shaft polylines for flow beads. HV cable polylines are per-pack, under
  // batteries.<key>.paths. Battery -> consumer is positive; reverse for regen.
  paths: {
    shaftFrontLeft: [[-0.20, 0.44, D.axleF], [-hubXF + 0.10, D.tireRadius, D.axleF]],
    shaftFrontRight: [[0.10, 0.44, D.axleF], [hubXF - 0.10, D.tireRadius, D.axleF]],
    shaftRearLeft: [[-0.24, 0.42, D.axleR], [-hubXR + 0.10, D.tireRadius, D.axleR]],
    shaftRearRight: [[0.16, 0.42, D.axleR], [hubXR - 0.10, D.tireRadius, D.axleR]],
    iceToMotor: [[0.13, 0.56, -1.44], [-0.30, 0.53, -1.42]],
  },
  // Framings verified against the preview; the widget one matches the crop the
  // 2D POWER card uses today.
  cameras: {
    hero: { position: [-4.55, 1.72, -4.95], target: [0, 0.42, -0.02], fov: 26 },
    // Near side-on and low, framed to match the reference photograph.
    reference: { position: [5.85, 1.62, -1.05], target: [-0.10, 0.44, -0.05], fov: 24 },
    widget: { position: [-3.45, 2.55, -4.55], target: [-0.05, 0.40, 0.02], fov: 25 },
    topDown: { position: [0.02, 8.40, 0.10], target: [0, 0.30, 0], fov: 32 },
    powerplant: { position: [-2.30, 1.15, -2.75], target: [-0.60, 0.36, -1.36], fov: 34 },
    battery: { position: [-1.35, 1.30, -1.55], target: [0, 0.26, -0.02], fov: 40 },
  },
};
const outJson = path.join(ROOT, 'assets', 'power', `haval-powertrain${suffix}.json`);
await fs.writeFile(outJson, JSON.stringify(manifest, null, 2) + '\n');

const kb = (await fs.stat(outGlb)).size / 1024;
console.log(
  `[powertrain] ${path.relative(ROOT, outGlb)}  ${kb.toFixed(1)} KB  ` +
  `${totalTris.toLocaleString()} tris  ${parts.length} parts  ${matCache.size} materials` +
  (DRACO ? '  (draco)' : ''),
);
