// Extracts the main <script type="text/x-dc"> block from index.html and runs
// it through `new Function` so a syntax error fails fast without a browser.
// Not a runtime test — it only proves the file parses.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const start = html.indexOf('<script type="text/x-dc" data-dc-script>');
if (start < 0) { console.error('no x-dc script block found'); process.exit(2); }
const from = html.indexOf('>', start) + 1;
const end = html.indexOf('</script>', from);
const js = html.slice(from, end);
try {
  new Function(js);
  console.log('SYNTAX OK (' + js.split('\n').length + ' lines)');
} catch (e) {
  console.error('SYNTAX ERROR:', e.message);
  process.exit(1);
}
