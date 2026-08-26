/**
 * Convert a Blender-exported Haval PHEV rim FBX (single corner, hub only)
 * into a catalog GLB: bake transforms, orient outer face to +Z, normalize
 * face diameter to ~1, strip emissive, Draco-compress when available.
 *
 * three r160+ rejects empty FBX anim curves — run once before extract:
 *   node scripts/patch-three-keyframe.mjs apply
 *   node scripts/extract-phev-wheel.mjs <in.fbx> <out.glb>
 *   node scripts/patch-three-keyframe.mjs restore
 *
 * Usage:
 *   node scripts/extract-phev-wheel.mjs <in.fbx> <out.glb>
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const projectDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// --- Node stubs so FBXLoader can resolve optional texture refs ---
globalThis.document = {
  createElementNS(_ns, name) {
    if (name === 'img') {
      const img = {
        _src: '',
        onload: null,
        onerror: null,
        width: 1,
        height: 1,
        addEventListener(type, fn) {
          if (type === 'load') this.onload = fn;
          if (type === 'error') this.onerror = fn;
        },
        removeEventListener() {},
        set src(v) {
          this._src = v;
          queueMicrotask(() => this.onload?.());
        },
        get src() {
          return this._src;
        },
      };
      return img;
    }
    return { style: {} };
  },
  createElement(name) {
    return this.createElementNS('', name);
  },
};
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
      Promise.resolve(blob.arrayBuffer())
        .then((buf) => {
          this.result = buf;
          this._done();
        })
        .catch((e) => this.onerror?.(e));
    }
    readAsDataURL(blob) {
      Promise.resolve(blob.arrayBuffer())
        .then((buf) => {
          const b64 = Buffer.from(buf).toString('base64');
          this.result = `data:${blob.type || 'application/octet-stream'};base64,${b64}`;
          this._done();
        })
        .catch((e) => this.onerror?.(e));
    }
  };
}

const DROP_RE =
  /tire|tyre|disk|disc|caliper|brake|rotor|background|road|rock|floor|ground|env|inner_map|dummy/i;
const HUB_RE = /hub|rim|spoke|bolt|cap|lug|chrome|plastic/i;
const inPath = path.resolve(process.argv[2] || '');
const outPath = path.resolve(process.argv[3] || '');
if (!inPath || !outPath || !fs.existsSync(inPath)) {
  console.error('Usage: node scripts/extract-phev-wheel.mjs <in.fbx> <out.glb>');
  process.exit(1);
}

const THREE = await import('three');
const { FBXLoader } = await import('three/examples/jsm/loaders/FBXLoader.js');
const { GLTFExporter } = await import('three/examples/jsm/exporters/GLTFExporter.js');

const buf = fs.readFileSync(inPath);
const group = new FBXLoader().parse(
  buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength),
  path.dirname(inPath) + path.sep,
);

const keepMeshes = [];
const undecided = [];
group.traverse((obj) => {
  if (!obj.isMesh) return;
  if (DROP_RE.test(obj.name || '')) {
    console.log(`drop ${obj.name}`);
    return;
  }
  if (HUB_RE.test(obj.name || '') || /PHEV/i.test(obj.name || '')) {
    keepMeshes.push(obj);
  } else {
    undecided.push(obj);
  }
});
// If nothing matched hub patterns, keep non-dropped meshes (PHEV34 style).
if (!keepMeshes.length) {
  for (const obj of undecided) keepMeshes.push(obj);
} else {
  for (const obj of undecided) console.log(`skip undecided: ${obj.name}`);
}
// Prefer "after" chrome over "before" when both exist
const names = new Set(keepMeshes.map((m) => m.name));
const filtered = keepMeshes.filter((m) => {
  if (/before_chrome/i.test(m.name) && [...names].some((n) => /after_chrome/i.test(n))) {
    console.log(`drop duplicate ${m.name} (have after_chrome)`);
    return false;
  }
  if (/before_plastic/i.test(m.name) && [...names].some((n) => /after_plastic/i.test(n))) {
    console.log(`drop duplicate ${m.name} (have after_plastic)`);
    return false;
  }
  return true;
});
keepMeshes.length = 0;
keepMeshes.push(...filtered);

const toStd = (m, fallbackName) => {
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
    if (c.emissive) c.emissive.setHex(0x000000);
    if ('emissiveIntensity' in c) c.emissiveIntensity = 0;
    return c;
  }
  const std = new THREE.MeshStandardMaterial();
  if (m.color) std.color.copy(m.color);
  else std.color.setHex(0x2a2a2a);
  if (m.map) {
    // Stubbed Node textures have no real image — drop so GLTFExporter won't
    // try to canvas-encode a fake 1×1.
    if (m.map.image && m.map.image.width > 1) {
      std.map = m.map;
      if (std.map.colorSpace !== undefined) std.map.colorSpace = THREE.SRGBColorSpace;
    }
  }
  if (m.normalMap) std.normalMap = m.normalMap;
  if (m.metalnessMap) std.metalnessMap = m.metalnessMap;
  if (m.roughnessMap) std.roughnessMap = m.roughnessMap;
  if (m.aoMap) std.aoMap = m.aoMap;
  std.emissive.setHex(0x000000);
  if ('emissiveIntensity' in std) std.emissiveIntensity = 0;
  if (typeof m.shininess === 'number') {
    std.roughness = Math.max(0.15, Math.min(0.9, 1 - m.shininess / 100));
    std.metalness = 0.8;
  } else {
    std.metalness = m.metalness ?? 0.8;
    std.roughness = m.roughness ?? 0.35;
  }
  // Chrome / mirror hubs: keep bright + metal
  if (/chrome|mirror/i.test(m.name || '') || (m.color && m.color.getHex() > 0xc0c0c0)) {
    std.metalness = 1.0;
    std.roughness = 0.12;
  }
  std.side = THREE.DoubleSide;
  std.name = m.name || fallbackName;
  return std;
};

const out = new THREE.Group();
out.name = path.basename(outPath, path.extname(outPath));
for (const mesh of keepMeshes) {
  mesh.updateWorldMatrix(true, false);
  const geom = mesh.geometry.clone();
  geom.applyMatrix4(mesh.matrixWorld);
  const mat = Array.isArray(mesh.material)
    ? mesh.material.map((m) => toStd(m, mesh.name))
    : toStd(mesh.material, mesh.name);
  const clone = new THREE.Mesh(geom, mat);
  clone.name = mesh.name;
  out.add(clone);
  console.log(
    `KEEP ${mesh.name}: ${geom.attributes.position.count} verts`,
  );
}

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

{
  const box = new THREE.Box3().setFromObject(out);
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const dims = [size.x, size.y, size.z];
  const axle = dims.indexOf(Math.min(...dims));

  let allMean = new THREE.Vector3();
  let nMeshes = 0;
  for (const ch of out.children) {
    if (!ch.isMesh) continue;
    allMean.add(meanOf(ch));
    nMeshes++;
  }
  if (nMeshes) allMean.multiplyScalar(1 / nMeshes);

  // Prefer chrome face mesh as outer-face marker when present
  const faceMesh =
    out.children.find((c) => /chrome|mirror/i.test(c.name)) ||
    out.children.find((c) => /hub_before|spoke|rim/i.test(c.name)) ||
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
  }

  const faceAfter =
    out.children.find((c) => /chrome|mirror/i.test(c.name)) || faceMesh;
  if (faceAfter?.isMesh && meanOf(faceAfter).z < 0) {
    const flip = new THREE.Matrix4().makeRotationY(Math.PI);
    for (const ch of out.children) {
      if (!ch.isMesh) continue;
      ch.geometry.applyMatrix4(flip);
      ch.geometry.computeVertexNormals();
    }
    console.log('Flipped 180° around Y (face was on -Z)');
  }

  const b2 = new THREE.Box3().setFromObject(out);
  const s = b2.getSize(new THREE.Vector3());
  const faceDiam = Math.max(s.x, s.y);
  const NORM = faceDiam > 1e-6 ? 1.0 / faceDiam : 1;
  if (Math.abs(NORM - 1) > 1e-6) {
    for (const ch of out.children) {
      if (!ch.isMesh) continue;
      ch.geometry.scale(NORM, NORM, NORM);
    }
  }
  const b3 = new THREE.Box3().setFromObject(out);
  const s3 = b3.getSize(new THREE.Vector3());
  console.log(
    `Oriented axle=${'xyz'[axle]} → +Z; BBox ${s3.x.toFixed(3)} x ${s3.y.toFixed(3)} x ${s3.z.toFixed(3)}`,
  );
}

const glb = await new Promise((resolve, reject) => {
  new GLTFExporter().parse(
    out,
    (result) => resolve(Buffer.from(result)),
    (err) => reject(err),
    { binary: true, onlyVisible: true, truncateDrawRange: true },
  );
});
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, glb);
console.log(`Wrote ${outPath} (${glb.length} bytes)`);

// Draco-compress in place when @gltf-transform is available (keeps MMI payloads small).
try {
  const { NodeIO } = await import('@gltf-transform/core');
  const { KHRDracoMeshCompression } = await import('@gltf-transform/extensions');
  const { draco } = await import('@gltf-transform/functions');
  const draco3d = (await import('draco3dgltf')).default;
  const io = new NodeIO()
    .registerExtensions([KHRDracoMeshCompression])
    .registerDependencies({
      'draco3d.decoder': await draco3d.createDecoderModule(),
      'draco3d.encoder': await draco3d.createEncoderModule(),
    });
  const doc = await io.read(outPath);
  await doc.transform(
    draco({
      method: 'edgebreaker',
      quantizePosition: 14,
      quantizeNormal: 10,
      quantizeTexcoord: 12,
      quantizeColor: 8,
    }),
  );
  const compressed = await io.writeBinary(doc);
  fs.writeFileSync(outPath, compressed);
  console.log(`Draco ${outPath} (${compressed.byteLength} bytes)`);
} catch (e) {
  console.warn('Draco compress skipped:', e?.message || e);
}
