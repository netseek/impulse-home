import fs from 'node:fs';

const path = process.argv[2] || 'index.html';
let c = fs.readFileSync(path, 'utf8');

const latin = 'U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD';
const latinExt = 'U+0100-02BA, U+02BD-02C5, U+02C7-02CC, U+02CE-02D7, U+02DD-02FF, U+0304, U+0308, U+0329, U+1D00-1DBF, U+1E00-1E9F, U+1EF2-1EFF, U+2020, U+20A0-20AB, U+20AD-20C0, U+2113, U+2C60-2C7F, U+A720-A7FF';

const faces = `/* Self-hosted UI font: DM Sans (400/600). latin-ext gated by unicode-range;
       latin already covers Portuguese accents. */
    @font-face {
      font-family: 'DM Sans'; font-style: normal; font-weight: 400; font-display: swap;
      src: url('vendor/fonts/dm-sans-latin-400.woff2') format('woff2');
      unicode-range: ${latin};
    }
    @font-face {
      font-family: 'DM Sans'; font-style: normal; font-weight: 600; font-display: swap;
      src: url('vendor/fonts/dm-sans-latin-600.woff2') format('woff2');
      unicode-range: ${latin};
    }
    @font-face {
      font-family: 'DM Sans'; font-style: normal; font-weight: 400; font-display: swap;
      src: url('vendor/fonts/dm-sans-latin-ext-400.woff2') format('woff2');
      unicode-range: ${latinExt};
    }
    @font-face {
      font-family: 'DM Sans'; font-style: normal; font-weight: 600; font-display: swap;
      src: url('vendor/fonts/dm-sans-latin-ext-600.woff2') format('woff2');
      unicode-range: ${latinExt};
    }
  </style>
  <style>
    :root { --hv-font: 'DM Sans', system-ui, sans-serif; }`;

const replaced = c.replace(
  /\/\* Self-hosted UI font[\s\S]*?:root \{ --hv-font: [^;]+; \}/,
  faces,
);
if (replaced === c) throw new Error('font-face block not matched');
c = replaced;

c = c.split("'Space Grotesk', system-ui, sans-serif").join("'DM Sans', system-ui, sans-serif");
c = c.split('"Space Grotesk", system-ui, sans-serif').join('"DM Sans", system-ui, sans-serif');
c = c.split('Space Grotesk, system-ui, sans-serif').join('DM Sans, system-ui, sans-serif');
c = c.split("'Space Grotesk',sans-serif").join("'DM Sans',sans-serif");
c = c.split("font-family=\"'Space Grotesk',sans-serif\"").join("font-family=\"'DM Sans',sans-serif\"");
c = c.replace(
  /Camera section: same type as the widgets and cards \(Space Grotesk,/,
  'Camera section: same type as the widgets and cards (DM Sans,',
);
c = c.replace(
  /\/\/ Option G\. Space Grotesk is the app's OWN packaged face/,
  "// Option G. DM Sans is the app's OWN packaged face",
);

fs.writeFileSync(path, c);
console.log(JSON.stringify({
  dmFaces: (c.match(/font-family: 'DM Sans'/g) || []).length,
  hvFont: (c.match(/--hv-font: 'DM Sans'/g) || []).length,
  leftoverGrotesk: (c.match(/Space Grotesk/g) || []).length,
}, null, 2));
