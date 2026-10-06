import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../assets/clock-faces.js', import.meta.url), 'utf8');
const snapshot = { time: '10:30', hour: '10', minute: '30', second: '12', dayPeriod: 'PM',
  weekdayShort: 'QUA', weekdayLong: 'QUARTA-FEIRA', day: '23', monthShort: 'SET.',
  monthLong: 'SETEMBRO', year: '2026', dateKey: '2026-09-23', hourAngle: 315,
  minuteAngle: 180, accessible: '10:30, quarta-feira 23 setembro' };
function check(code) {
  const sandbox = {};
  vm.runInNewContext(code, sandbox);
  const faces = sandbox.H6ClockFaces;
  for (const face of Object.keys(faces.names)) {
    for (const [size, width, height] of [['1x1',342,194],['1x2',342,396],['2x1',692,194],['2x2',692,396]]) {
      const svg = faces.render(face, snapshot, { size });
      const [, w, h] = svg.match(/viewBox="0 0 (\d+) (\d+)"/).map(Number);
      const scale = Math.min(width / w, height / h);
      const floor = size === '1x1' ? 18 : 20;
      for (const text of svg.matchAll(/<text[^>]*font-size="([\d.]+)"[^>]*>([^<]+)<\/text>/g)) {
        assert.ok(Number(text[1]) * scale >= floor, `${face} ${size}: ${text[2]} below ${floor}px`);
      }
    }
  }
}
check(source);
const broken = source.replace(/if \(size !== 'rail'\) textSize = Math.max\([^;]+;/, '');
assert.notEqual(broken, source, 'negative control removes the widget scale floor');
assert.throws(() => check(broken), /below (18|20)px/);
console.log('PASS effective clock text size in every widget face/footprint + negative control');
