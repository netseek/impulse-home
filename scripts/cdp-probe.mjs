const list = await fetch('http://127.0.0.1:9222/json').then((r) => r.json());
const page = list.find((p) => p.type === 'page' && p.webSocketDebuggerUrl);
if (!page) {
  console.error('no page', list);
  process.exit(1);
}

const expr = process.argv[2] || `(() => {
  const a = window.__app;
  const r = document.getElementById('hv-root');
  const rs = r && getComputedStyle(r);
  const c = document.querySelector('canvas');
  const cs = c && getComputedStyle(c);
  return {
    inner: [window.innerWidth, window.innerHeight],
    screen: [window.screen && screen.width, window.screen && screen.height],
    doc: [document.documentElement.clientWidth, document.documentElement.clientHeight],
    body: [document.body.clientWidth, document.body.clientHeight],
    rootInline: r && r.getAttribute('style'),
    rootComputed: rs && { pos: rs.position, top: rs.top, left: rs.left, w: rs.width, h: rs.height },
    rootClient: r && [r.clientWidth, r.clientHeight],
    canvasInline: c && { w: c.style.width, h: c.style.height, bw: c.width, bh: c.height },
    canvasComputed: cs && { w: cs.width, h: cs.height, pos: cs.position, display: cs.display },
    canvasClient: c && [c.clientWidth, c.clientHeight],
    canvasParent: c && c.parentNode && { tag: c.parentNode.tagName, id: c.parentNode.id, cw: c.parentNode.clientWidth, ch: c.parentNode.clientHeight, style: c.parentNode.getAttribute('style') },
    hasLayoutFix: !!document.getElementById('hv-layout-fix'),
  };
})()`;

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve);
  ws.addEventListener('error', reject);
});
const result = await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('timeout')), 8000);
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id === 1) {
      clearTimeout(t);
      resolve(msg);
    }
  });
  ws.send(JSON.stringify({
    id: 1,
    method: 'Runtime.evaluate',
    params: { expression: expr, returnByValue: true },
  }));
});
console.log(JSON.stringify(result.result || result, null, 2));
ws.close();
