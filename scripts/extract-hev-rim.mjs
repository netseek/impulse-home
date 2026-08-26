/**
 * Extract ONE wheel's rim + wheel_cap + bolts from HavalHEV-wheels.fbx → GLB.
 * Source FBX has 4 corners merged into each mesh; we keep the cluster nearest
 * the most-negative X/Z corner (one wheel), drop tires/disk/calipers.
 *
 * Usage:
 *   node scripts/extract-hev-rim.mjs [path-to.fbx] [out.glb]
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

if (typeof globalThis.FileReader === 'undefined') {
  globalThis.FileReader = class {
    constructor() {
      this.onload = null;
      this.onloadend = null;
      this.onerror = null;
      this.result = null;
    }
    _done() {
      this.onload?.({ target: this });
      this.onloadend?.({ target: this });
    }
    readAsArrayBuffer(blob) {
      Promise.resolve(blob.arrayBuffer()).then((buf) => {
        this.result = buf;
        this._done();
      }).catch((err) => this.onerror?.(err));
    }
    readAsDataURL(blob) {
      Promise.resolve(blob.arrayBuffer()).then((buf) => {
        const b64 = Buffer.from(buf).toString('base64');
        const type = blob.type || 'application/octet-stream';
        this.result = `data:${type};base64,${b64}`;
        this._done();
      }).catch((err) => this.onerror?.(err));
    }
  };
}

const KEEP = new Set(['rim', 'wheel_cap', 'model_14', 'bolts']);
const DROP = new Set(['tires', 'disk', 'calipers']);

const inPath = path.resolve(process.argv[2] || 'F:/Blender/Haval/HavalHEV-wheels.fbx');
const outPath = path.resolve(process.argv[3] || path.join(projectDir, 'assets/wheels/HavalHEV-wheel.glb'));

const THREE = await import('three');
const { FBXLoader } = await import('three/examples/jsm/loaders/FBXLoader.js');
const { GLTFExporter } = await import('three/examples/jsm/exporters/GLTFExporter.js');

const buf = fs.readFileSync(inPath);
const loader = new FBXLoader();
const group = loader.parse(
  buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength),
  path.dirname(inPath) + path.sep,
);

const keepMeshes = [];
group.traverse((obj) => {
  if (!obj.isMesh) return;
  const n = (obj.name || '').toLowerCase();
  if (DROP.has(n) || /tire|tyre|disk|disc|caliper|brake|rotor/i.test(obj.name)) return;
  if (KEEP.has(obj.name) || KEEP.has(n) || /^(rim|wheel_cap|model_14|bolts)(\.\d+)?$/i.test(obj.name) || /bolt|lug|nut/i.test(obj.name)) {
    keepMeshes.push(obj);
  } else {
    console.log(`skip undecided: ${obj.name}`);
  }
});

if (!keepMeshes.length) {
  console.error('No rim/cap/bolt meshes found');
  process.exit(1);
}

/** Collect world-space XYZ samples from all keep meshes to find 4 wheel centers. */
function sampleCenters(meshes, stride = 24) {
  const pts = [];
  const v = new THREE.Vector3();
  for (const mesh of meshes) {
    mesh.updateWorldMatrix(true, false);
    const pos = mesh.geometry.attributes.position;
    for (let i = 0; i < pos.count; i += stride) {
      v.fromBufferAttribute(pos, i).applyMatrix4(mesh.matrixWorld);
      pts.push([v.x, v.y, v.z]);
    }
  }
  return pts;
}

/** K-means in XZ (ignore Y) → 4 wheel hubs. */
function kmeansXZ(pts, k = 4, iters = 24) {
  // seed with extreme corners
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (const p of pts) {
    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
    minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]);
  }
  const seeds = [
    [minX, 0, minZ],
    [minX, 0, maxZ],
    [maxX, 0, minZ],
    [maxX, 0, maxZ],
  ];
  let centers = seeds.slice(0, k);
  let assign = new Array(pts.length).fill(0);
  for (let it = 0; it < iters; it++) {
    for (let i = 0; i < pts.length; i++) {
      let best = 0, bestD = Infinity;
      for (let c = 0; c < k; c++) {
        const dx = pts[i][0] - centers[c][0];
        const dz = pts[i][2] - centers[c][2];
        const d = dx * dx + dz * dz;
        if (d < bestD) { bestD = d; best = c; }
      }
      assign[i] = best;
    }
    const next = Array.from({ length: k }, () => [0, 0, 0, 0]);
    for (let i = 0; i < pts.length; i++) {
      const a = assign[i];
      next[a][0] += pts[i][0];
      next[a][1] += pts[i][1];
      next[a][2] += pts[i][2];
      next[a][3] += 1;
    }
    centers = next.map((n, i) => (n[3] ? [n[0] / n[3], n[1] / n[3], n[2] / n[3]] : centers[i]));
  }
  return centers;
}

const samples = sampleCenters(keepMeshes);
const hubs = kmeansXZ(samples, 4);
// Pick the hub with most-negative X+Z (one consistent corner)
hubs.sort((a, b) => (a[0] + a[2]) - (b[0] + b[2]));
const hub = hubs[0];
console.log('Wheel hubs (XZ):');
for (const h of hubs) console.log(`  (${h[0].toFixed(1)}, ${h[2].toFixed(1)})`);
console.log(`Keeping hub (${hub[0].toFixed(1)}, ${hub[2].toFixed(1)})`);

// Half-way between this hub and the nearest other hub → cluster radius
let nearest = Infinity;
for (let i = 1; i < hubs.length; i++) {
  const dx = hubs[i][0] - hub[0];
  const dz = hubs[i][2] - hub[2];
  nearest = Math.min(nearest, Math.hypot(dx, dz));
}
const radius = nearest * 0.45;
const radiusSq = radius * radius;
console.log(`Cluster radius ${radius.toFixed(1)}`);

function extractClusterGeometry(mesh, hub, radiusSq) {
  mesh.updateWorldMatrix(true, false);
  const geom = mesh.geometry;
  const pos = geom.attributes.position;
  const idx = geom.index;
  const inv = new THREE.Matrix4().copy(mesh.matrixWorld).invert();
  const v = new THREE.Vector3();
  const keepVert = new Uint8Array(pos.count);

  for (let i = 0; i < pos.count; i++) {
    v.fromBufferAttribute(pos, i).applyMatrix4(mesh.matrixWorld);
    const dx = v.x - hub[0];
    const dz = v.z - hub[2];
    if (dx * dx + dz * dz <= radiusSq) keepVert[i] = 1;
  }

  const newPos = [];
  const newNorm = [];
  const newUv = [];
  const newIdx = [];
  const remap = new Int32Array(pos.count).fill(-1);
  const hasN = !!geom.attributes.normal;
  const hasUv = !!geom.attributes.uv;
  const nAttr = geom.attributes.normal;
  const uvAttr = geom.attributes.uv;

  const addVert = (i) => {
    if (remap[i] >= 0) return remap[i];
    const id = newPos.length / 3;
    remap[i] = id;
    // bake world transform into positions, then we'll recenter later
    v.fromBufferAttribute(pos, i).applyMatrix4(mesh.matrixWorld);
    newPos.push(v.x, v.y, v.z);
    if (hasN) {
      v.fromBufferAttribute(nAttr, i).transformDirection(mesh.matrixWorld).normalize();
      newNorm.push(v.x, v.y, v.z);
    }
    if (hasUv) newUv.push(uvAttr.getX(i), uvAttr.getY(i));
    return id;
  };

  const triCount = idx ? idx.count / 3 : pos.count / 3;
  let keptTris = 0;
  for (let t = 0; t < triCount; t++) {
    const a = idx ? idx.getX(t * 3) : t * 3;
    const b = idx ? idx.getX(t * 3 + 1) : t * 3 + 1;
    const c = idx ? idx.getX(t * 3 + 2) : t * 3 + 2;
    // keep triangle if majority of verts are in cluster (handles boundary)
    const votes = keepVert[a] + keepVert[b] + keepVert[c];
    if (votes < 2) continue;
    newIdx.push(addVert(a), addVert(b), addVert(c));
    keptTris++;
  }

  if (!keptTris) return null;

  const out = new THREE.BufferGeometry();
  out.setAttribute('position', new THREE.Float32BufferAttribute(newPos, 3));
  if (hasN && newNorm.length) out.setAttribute('normal', new THREE.Float32BufferAttribute(newNorm, 3));
  else out.computeVertexNormals();
  if (hasUv && newUv.length) out.setAttribute('uv', new THREE.Float32BufferAttribute(newUv, 2));
  out.setIndex(newIdx);
  return out;
}

const toStd = (m, fallbackName) => {
  // Preserve authored colours/maps. Do not force-lighten — HEV stock is dark metal.
  if (!m) {
    return new THREE.MeshStandardMaterial({
      color: 0x2a2a2a,
      metalness: 0.85,
      roughness: 0.35,
      name: fallbackName,
    });
  }
  if (m.isMeshStandardMaterial || m.isMeshPhysicalMaterial) {
    const c = m.clone();
    c.side = THREE.DoubleSide;
    // FBX often carries full-white emissive that turns the rim into a flat
    // glowing disc in the viewer and hides lip/spoke depth.
    if (c.emissive) c.emissive.setHex(0x000000);
    if ('emissiveIntensity' in c) c.emissiveIntensity = 0;
    return c;
  }
  const std = new THREE.MeshStandardMaterial();
  if (m.color) std.color.copy(m.color);
  else std.color.setHex(0x2a2a2a);
  if (m.map) {
    std.map = m.map;
    if (std.map.colorSpace !== undefined) std.map.colorSpace = THREE.SRGBColorSpace;
  }
  if (m.normalMap) std.normalMap = m.normalMap;
  if (m.emissiveMap) std.emissiveMap = m.emissiveMap;
  // Ignore Phong/FBX emissive colour — treat as non-emissive metal unless a map exists.
  if (m.emissive && m.emissiveMap) std.emissive.copy(m.emissive);
  else std.emissive.setHex(0x000000);
  if ('emissiveIntensity' in std) std.emissiveIntensity = 0;
  if (m.aoMap) std.aoMap = m.aoMap;
  // Phong shininess → rough metal estimate
  if (typeof m.shininess === 'number') {
    std.roughness = Math.max(0.15, Math.min(0.9, 1 - m.shininess / 100));
    std.metalness = 0.8;
  } else {
    std.metalness = m.metalness ?? 0.8;
    std.roughness = m.roughness ?? 0.35;
  }
  std.side = THREE.DoubleSide;
  std.name = m.name || fallbackName;
  return std;
};

const out = new THREE.Group();
out.name = 'HavalHEV_rim';
for (const mesh of keepMeshes) {
  const geom = extractClusterGeometry(mesh, hub, radiusSq);
  if (!geom) {
    console.warn(`no cluster geometry for ${mesh.name}`);
    continue;
  }
  const mat = Array.isArray(mesh.material)
    ? mesh.material.map((m) => toStd(m, mesh.name))
    : toStd(mesh.material, mesh.name);
  const clone = new THREE.Mesh(geom, mat);
  clone.name = mesh.name;
  out.add(clone);
  console.log(`KEEP ${mesh.name}: ${geom.attributes.position.count} verts, ${(geom.index.count / 3) | 0} tris`);
}

// Recenter + orient so outer face (wheel_cap / bolts) points +Z.
{
  const box = new THREE.Box3().setFromObject(out);
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const dims = [size.x, size.y, size.z];
  const axle = dims.indexOf(Math.min(...dims));

  const meanOf = (mesh) => {
    const pos = mesh.geometry.attributes.position;
    const m = new THREE.Vector3();
    for (let i = 0; i < pos.count; i++) {
      m.x += pos.getX(i);
      m.y += pos.getY(i);
      m.z += pos.getZ(i);
    }
    return m.multiplyScalar(1 / pos.count);
  };

  let allMean = new THREE.Vector3();
  let nMeshes = 0;
  for (const ch of out.children) {
    if (!ch.isMesh) continue;
    allMean.add(meanOf(ch));
    nMeshes++;
  }
  if (nMeshes) allMean.multiplyScalar(1 / nMeshes);

  const faceMesh =
    out.getObjectByName('wheel_cap') ||
    out.getObjectByName('bolts') ||
    out.children.find((c) => c.isMesh);
  const faceMean = faceMesh ? meanOf(faceMesh) : allMean.clone();
  const faceDelta = faceMean.clone().sub(allMean);
  let faceSign = Math.sign(faceDelta.getComponent(axle));
  if (!faceSign) faceSign = 1;

  const faceDir = new THREE.Vector3();
  faceDir.setComponent(axle, faceSign);
  const quat = new THREE.Quaternion().setFromUnitVectors(
    faceDir.clone().normalize(),
    new THREE.Vector3(0, 0, 1),
  );

  // Bake orientation + recenter into geometry so GLB root is identity.
  out.updateMatrixWorld(true);
  const bake = new THREE.Matrix4()
    .makeRotationFromQuaternion(quat)
    .multiply(new THREE.Matrix4().makeTranslation(-center.x, -center.y, -center.z));

  for (const ch of out.children) {
    if (!ch.isMesh) continue;
    ch.geometry.applyMatrix4(bake);
    ch.geometry.computeVertexNormals();
    ch.position.set(0, 0, 0);
    ch.quaternion.identity();
    ch.scale.set(1, 1, 1);
    ch.updateMatrix();
  }
  out.position.set(0, 0, 0);
  out.quaternion.identity();
  out.scale.set(1, 1, 1);
  out.updateMatrixWorld(true);

  // Verify outer face landed on +Z. faceSign from mean delta can invert on
  // deep-dish rims (rim body mean sits past the cap), leaving the back facing
  // the camera — looks like a too-small wheel inside the tire bore.
  const faceAfter = out.getObjectByName('wheel_cap') || out.getObjectByName('bolts') || faceMesh;
  if (faceAfter?.isMesh) {
    const fz = meanOf(faceAfter).z;
    if (fz < 0) {
      const flip = new THREE.Matrix4().makeRotationY(Math.PI);
      for (const ch of out.children) {
        if (!ch.isMesh) continue;
        ch.geometry.applyMatrix4(flip);
        ch.geometry.computeVertexNormals();
      }
      console.log(`Flipped 180° around Y (face mean Z was ${fz.toFixed(3)})`);
    } else {
      console.log(`Face mean Z OK (${fz.toFixed(3)})`);
    }
  }

  const b2 = new THREE.Box3().setFromObject(out);
  const s = b2.getSize(new THREE.Vector3());
  const faceDiam = Math.max(s.x, s.y);
  // Normalize to ~1 unit face diameter like other catalog rims (PHEV≈0.98).
  // Absolute Blender units confuse nothing in theory (runtime rescales), but a
  // huge depth/diameter ratio in raw units still makes mounting heuristics noisier.
  const NORM = faceDiam > 1e-6 ? 1.0 / faceDiam : 1;
  if (Math.abs(NORM - 1) > 1e-6) {
    for (const ch of out.children) {
      if (!ch.isMesh) continue;
      ch.geometry.scale(NORM, NORM, NORM);
    }
  }
  const b3 = new THREE.Box3().setFromObject(out);
  const s3 = b3.getSize(new THREE.Vector3());
  const faceCheck = out.getObjectByName('wheel_cap') || out.getObjectByName('bolts');
  const faceZ = faceCheck?.isMesh ? meanOf(faceCheck).z : NaN;
  console.log(`Oriented axle=${'xyz'[axle]} faceSign=${faceSign} → +Z (faceZ=${faceZ.toFixed(3)})`);
  console.log(`BBox: ${s3.x.toFixed(3)} x ${s3.y.toFixed(3)} x ${s3.z.toFixed(3)} (normalized from diam ${faceDiam.toFixed(1)})`);
}

const exporter = new GLTFExporter();
const glb = await new Promise((resolve, reject) => {
  exporter.parse(
    out,
    (result) => resolve(Buffer.from(result)),
    (err) => reject(err),
    { binary: true, onlyVisible: true, truncateDrawRange: true },
  );
});

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, glb);
console.log(`Wrote ${outPath} (${glb.length} bytes)`);
