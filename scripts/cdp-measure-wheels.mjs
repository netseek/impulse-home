/**
 * Measure installed Haval catalog wheel radius on a live viewer via CDP.
 * Usage: node scripts/cdp-measure-wheels.mjs [wsUrl]
 */
import WebSocket from 'ws';

const WS =
  process.argv[2] ||
  'ws://127.0.0.1:9222/devtools/page/0AE7F3F7C50FD62F1E6DFD94079E46D7';

const keys = ['haval_gt', 'haval_phev', 'haval_phev19', 'haval_hev'];

function cdp(ws, method, params = {}) {
  const id = Math.floor(Math.random() * 1e9);
  ws.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timeout ${method}`)), 120000);
    const onMsg = (raw) => {
      const msg = JSON.parse(raw);
      if (msg.id !== id) return;
      clearTimeout(timer);
      ws.off('message', onMsg);
      if (msg.error) reject(new Error(JSON.stringify(msg.error)));
      else resolve(msg.result);
    };
    ws.on('message', onMsg);
  });
}

const loadExpr = (key) => `
new Promise((resolve) => {
  const app = window.__app;
  if (!app) { resolve({ err: 'no app' }); return; }
  const start = () => {
    app.setState({ wheelSizeInch: 19, wheelStyle: 'custom' });
    app._loadCatalogWheelByKey('${key}', () => {
      app.onWheelSizeInch(19);
      setTimeout(() => resolve({ ok: true, key: '${key}' }), 3000);
    });
  };
  const deadline = Date.now() + 120000;
  const waitReady = () => {
    if (app.scene && !app.state.loading) start();
    else if (Date.now() > deadline) resolve({ err: 'app not ready' });
    else setTimeout(waitReady, 500);
  };
  waitReady();
})
`;

const measureExpr = `
(() => {
  const app = window.__app;
  const THREE = app.THREE;
  const fl = app._frontLeftWheels?.[0];
  if (!fl) return { err: 'no FL wheel', key: app.state.selectedCatalogKey };
  fl.updateWorldMatrix(true, true);
  const ws = new THREE.Vector3();
  fl.getWorldScale(ws);
  const item = app._wheelCatalog.find(i => i.key === app.state.selectedCatalogKey);
  return {
    requestedKey: app._pendingCatalogFitKey || app.state.catalogLoadingKey || app.state.selectedCatalogKey,
    key: app.state.selectedCatalogKey,
    inch: app.state.wheelSizeInch,
    fitScale: item?.fitScale || 1,
    localScaleXY: fl.scale.x,
    worldScaleXY: ws.x,
    label: item?.label || null,
  };
})()
`;

const ws = new WebSocket(WS);
ws.on('open', async () => {
  try {
    await cdp(ws, 'Runtime.enable');
    const rows = [];
    for (const key of keys) {
      console.log('loading', key);
      await cdp(ws, 'Runtime.evaluate', {
        expression: loadExpr(key),
        awaitPromise: true,
        returnByValue: true,
      });
      const m = await cdp(ws, 'Runtime.evaluate', {
        expression: measureExpr,
        returnByValue: true,
      });
      rows.push(m.result.value);
      console.log(m.result.value);
    }
    const ref = rows.find((r) => r.key === 'haval_phev' && r.worldScaleXY) || rows.find((r) => r.worldScaleXY);
    console.log('\nRelative to', ref?.key, 'worldScaleXY', ref?.worldScaleXY?.toFixed(5));
    for (const r of rows) {
      if (!r.worldScaleXY) { console.log(r); continue; }
      console.log(`${r.key}: worldScaleXY=${r.worldScaleXY.toFixed(5)} fitScale~${(ref.worldScaleXY / r.worldScaleXY).toFixed(3)} (catalog fitScale=${r.fitScale})`);
    }
    ws.close();
  } catch (e) {
    console.error(e);
    ws.close();
    process.exit(1);
  }
});
