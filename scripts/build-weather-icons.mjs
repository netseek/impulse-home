// Weather glyphs for the CLIMATIZAÇÃO rail card.
//
//   node scripts/build-weather-icons.mjs
//
// Material Design Icons (@mdi/svg, Apache-2.0) rasterised white-on-transparent
// at 96 px: the card is drawn natively on a Canvas, which cannot take an SVG,
// and a white bitmap can be tinted to the card's colour with a ColorFilter.
// Names map to WMO weather codes in _weatherIconFor (index.html).
import sharp from 'sharp';
import fs from 'node:fs';

const OUT = 'assets/ui/icons/weather';
const SIZE = 96;
const ICONS = ['weather-sunny', 'weather-partly-cloudy', 'weather-cloudy', 'weather-fog',
  'weather-rainy', 'weather-pouring', 'weather-snowy', 'weather-lightning', 'weather-night'];

fs.mkdirSync(OUT, { recursive: true });
for (const name of ICONS) {
  const res = await fetch(`https://cdn.jsdelivr.net/npm/@mdi/svg@7.4.47/svg/${name}.svg`);
  if (!res.ok) { console.warn('MISSING', name, res.status); continue; }
  const svg = (await res.text()).replace('<svg ', '<svg fill="#ffffff" ');
  const out = `${OUT}/${name.replace('weather-', '')}.png`;
  await sharp(Buffer.from(svg), { density: 384 }).resize(SIZE, SIZE).png().toFile(out);
  console.log(out, Math.round(fs.statSync(out).size / 1024) + ' kB');
}
