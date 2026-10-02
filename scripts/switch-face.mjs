import { execSync } from 'child_process';

function ensureForward() {
  const adb = (process.env.LOCALAPPDATA || '') + '\\Android\\Sdk\\platform-tools\\adb.exe';
  try {
    const unixNet = execSync(`"${adb}" -s emulator-5554 shell "grep -a webview_devtools_remote /proc/net/unix"`, { stdio: ['pipe', 'pipe', 'ignore'] }).toString();
    const match = unixNet.match(/@webview_devtools_remote_(\d+)/);
    if (match) {
      execSync(`"${adb}" -s emulator-5554 forward tcp:9222 localabstract:webview_devtools_remote_${match[1]}`);
    }
  } catch (e) {}
}

const face = process.argv[2] || 'meridian';

async function run() {
  ensureForward();
  const res = await fetch('http://127.0.0.1:9222/json');
  const targets = await res.json();
  const page = targets.find(t => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);

  return new Promise((resolve, reject) => {
    ws.onopen = () => {
      ws.send(JSON.stringify({
        id: 1,
        method: 'Runtime.evaluate',
        params: {
          expression: `(() => {
            window.__app._setClockConfig('widget', 'style', '${face}');
            return window.__app._widgetClockConfig;
          })()`,
          returnByValue: true
        }
      }));
    };
    ws.onmessage = (msg) => {
      ws.close();
      resolve(JSON.parse(msg.data).result.result.value);
    };
    ws.onerror = reject;
  });
}

run().then(res => console.log('Switched to:', res)).catch(console.error);
