// Fetch the CLIMA icon set and emit its path data.
//
//   node scripts/build-clima-icons.mjs
//
// Source: Material Design Icons (@mdi/svg, Apache-2.0, Pictogrammers). They are
// the only open set with the automotive climate glyphs this popup needs —
// windscreen and rear-window defrost, seat cooling, cabin air filter.
// Airflow DIRECTION (face / feet / face+feet / feet+windscreen) is not in MDI;
// those four come from Impulse's own drawables instead — see
// assets/ui/icons/clima/blower-*.png and build-clima-blower-icons below.
//
// Writes assets/ui/icons/clima/icons.json: { name: "<path d>" }, 24x24 grid.
// index.html embeds the map (CLIM_ICONS) so the glyphs inherit currentColor.
import fs from 'node:fs';
import path from 'node:path';

const VERSION = '7.4.47';
const OUT = 'assets/ui/icons/clima';
// Checkout of the sibling Impulse repository (set IMPULSE_DIR to override).
const IMPULSE_DRAWABLE = `${process.env.IMPULSE_DIR || '../haval-app-tool-multimidia'}/app/src/main/res/drawable`;

// popup name -> MDI icon
const ICONS = {
  power: 'power',
  ac: 'air-conditioner',
  auto: 'fan-auto',
  sync: 'sync',
  link: 'link-variant',
  recirc: 'autorenew',
  front: 'car-defrost-front',
  rear: 'car-defrost-rear',
  seatVent: 'car-seat-cooler',
  fan: 'fan',
  air: 'air-filter',
  purifier: 'air-purifier',
  heat: 'heat-wave',
  tune: 'tune-variant',
  thermometer: 'thermometer',
  snow: 'snowflake',
  clock: 'timer-outline',
};

fs.mkdirSync(OUT, { recursive: true });
const out = {};
for (const [name, mdi] of Object.entries(ICONS)) {
  const url = `https://cdn.jsdelivr.net/npm/@mdi/svg@${VERSION}/svg/${mdi}.svg`;
  const res = await fetch(url);
  if (!res.ok) { console.warn('MISSING', mdi, res.status); continue; }
  const svg = await res.text();
  const d = /\sd="([^"]+)"/.exec(svg);
  if (!d) { console.warn('no path in', mdi); continue; }
  out[name] = d[1];
  console.log(`${name.padEnd(12)} <- mdi/${mdi}`);
}
fs.writeFileSync(path.join(OUT, 'icons.json'), JSON.stringify(out, null, 1) + '\n');

// Airflow direction: Impulse's own 32px glyphs, copied as-is.
for (const [name, file] of Object.entries({
  face: 'ic_hvac_blower_face.png',
  facefeet: 'ic_hvac_blower_feet_and_face.png',
  feet: 'ic_hvac_blower_feet.png',
  feetglass: 'ic_hvac_blower_feet_and_defrost.png',
})) {
  const src = path.join(IMPULSE_DRAWABLE, file);
  if (!fs.existsSync(src)) { console.warn('missing Impulse drawable', src); continue; }
  fs.copyFileSync(src, path.join(OUT, `blower-${name}.png`));
  console.log(`blower-${name}.png <- impulse/${file}`);
}
console.log('\nicons.json entries:', Object.keys(out).length);
