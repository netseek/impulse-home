import { execSync } from 'child_process';

function ensureForward() {
  const adb = (process.env.LOCALAPPDATA || 'C:\\Users\\<user>\\AppData\\Local') + '\\Android\\Sdk\\platform-tools\\adb.exe';
  try {
    const unixNet = execSync(`"${adb}" -s emulator-5554 shell "grep -a webview_devtools_remote /proc/net/unix"`, { stdio: ['pipe', 'pipe', 'ignore'] }).toString();
    const match = unixNet.match(/@webview_devtools_remote_(\d+)/);
    if (match) {
      execSync(`"${adb}" -s emulator-5554 forward tcp:9222 localabstract:webview_devtools_remote_${match[1]}`);
    }
  } catch (e) {}
}

async function inspect() {
  ensureForward();
  const res = await fetch('http://127.0.0.1:9222/json');
  const targets = await res.json();
  const page = targets.find(t => t.type === 'page');
  if (!page) throw new Error('No page target');

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  return new Promise((resolve, reject) => {
    ws.onopen = () => {
      ws.send(JSON.stringify({
        id: 1,
        method: 'Runtime.evaluate',
        params: {
          expression: `(() => {
            const clocks = Array.from(document.querySelectorAll("h6-clock"));
            return clocks.map(c => ({
              surface: c.getAttribute("surface"),
              size: c.getAttribute("size"),
              style: c.getAttribute("clock-style"),
              w: c.offsetWidth,
              h: c.offsetHeight,
              parentClass: c.parentElement ? c.parentElement.className : null,
              parentW: c.parentElement ? c.parentElement.offsetWidth : null,
              parentH: c.parentElement ? c.parentElement.offsetHeight : null,
              parentR: c.parentElement ? window.getComputedStyle(c.parentElement).borderRadius : null
            }));
          })()`,
          returnByValue: true
        }
      }));
    };
    ws.onmessage = (msg) => {
      const data = JSON.parse(msg.data);
      if (data.id === 1) {
        ws.close();
        resolve(data.result.result.value);
      }
    };
    ws.onerror = reject;
  });
}

inspect().then(res => console.log(JSON.stringify(res, null, 2))).catch(console.error);
