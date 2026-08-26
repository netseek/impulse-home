/**
 * Dump mesh names from an FBX (with stubs so textured FBX parse in Node).
 *   node scripts/dump-fbx-meshes.mjs path.fbx
 */
import fs from 'node:fs';
import path from 'node:path';

globalThis.document = {
  createElementNS(_ns, name) {
    if (name === 'img') {
      return {
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
          this.result =
            'data:application/octet-stream;base64,' + Buffer.from(buf).toString('base64');
          this._done();
        })
        .catch((e) => this.onerror?.(e));
    }
  };
}

const { FBXLoader } = await import('three/examples/jsm/loaders/FBXLoader.js');
const inPath = path.resolve(process.argv[2] || '');
if (!inPath || !fs.existsSync(inPath)) {
  console.error('Usage: node scripts/dump-fbx-meshes.mjs <file.fbx>');
  process.exit(1);
}
const buf = fs.readFileSync(inPath);
const g = new FBXLoader().parse(
  buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength),
  path.dirname(inPath) + path.sep,
);
const meshes = [];
g.traverse((o) => {
  if (!o.isMesh) return;
  const mats = Array.isArray(o.material) ? o.material : [o.material];
  meshes.push({
    name: o.name,
    verts: o.geometry?.attributes?.position?.count,
    mats: mats.map((m) => ({
      name: m?.name,
      map: !!m?.map,
      color: m?.color?.getHexString?.(),
    })),
  });
});
console.log(path.basename(inPath));
console.log(JSON.stringify(meshes, null, 2));
