// Pull the two remaining CLIMA glyphs into assets/ui/icons/clima.
//
//   node scripts/build-clima-extra-icons.mjs
//
//  * recirculation: Impulse draws it from two base64 PNGs inlined in
//    BottomBarUI.kt (recycleIn = recirculating, recycleOut = fresh air).
//    Flattened to a white-on-transparent mask so the popup can tint them.
//  * the seat glyph: a Noun Project icon supplied by the owner
//    (noun_heatedseat_2695898, "Created by Thuy Nguyen from the Noun Project").
//    The two attribution <text> nodes are stripped from the shipped copy —
//    the credit lives in docs/credits.md instead, where it can be read.
import fs from 'node:fs';
import path from 'node:path';

const OUT = 'assets/ui/icons/clima';
const BOTTOM_BAR = 'C:/Users/<user>/StudioProjects/haval-app-tool-multimidia/app/src/main/java/br/com/redesurftank/havalshisuku/ui/components/BottomBarUI.kt';
const SEAT_SVG = 'C:/Users/<user>/Downloads/noun_heatedseat_2695898.svg';

fs.mkdirSync(OUT, { recursive: true });

const kt = fs.readFileSync(BOTTOM_BAR, 'utf8');
for (const [name, konst] of Object.entries({ 'recirc-in': 'recycleIn', 'recirc-out': 'recycleOut' })) {
  const m = new RegExp(`val ${konst} = "data:image/png;base64,([A-Za-z0-9+/=]+)"`).exec(kt);
  if (!m) { console.warn('not found in BottomBarUI.kt:', konst); continue; }
  const file = path.join(OUT, `${name}.png`);
  fs.writeFileSync(file, Buffer.from(m[1], 'base64'));
  console.log(`${name}.png  <- impulse/${konst}  ${Math.round(fs.statSync(file).size / 1024)} kB`);
}

if (fs.existsSync(SEAT_SVG)) {
  const svg = fs.readFileSync(SEAT_SVG, 'utf8')
    .replace(/<text[\s\S]*?<\/text>/g, '')
    .replace(/viewBox="0 0 33 41.25"/, 'viewBox="0 0 33 33"');
  fs.writeFileSync(path.join(OUT, 'seat.svg'), svg);
  const d = [...svg.matchAll(/<path d="([^"]+)"/g)].map((x) => x[1]);
  fs.writeFileSync(path.join(OUT, 'seat-paths.json'), JSON.stringify(d, null, 1) + '\n');
  console.log(`seat.svg    <- noun_heatedseat_2695898 (${d.length} paths, attribution in docs/credits.md)`);
} else {
  console.warn('seat source not found:', SEAT_SVG);
}
