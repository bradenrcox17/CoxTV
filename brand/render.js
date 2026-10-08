// Renders every CoxTV brand asset from one master design with headless Chrome.
// node render.js  -> out/<file>.png
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const DIR = __dirname;
const OUT = path.join(DIR, 'out');
const HTML = path.join(DIR, 'html');
fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(HTML, { recursive: true });

const ORANGE = '#FF8200';
const GROUND = '#0A0A0B';
const TILE = '#141416';
const TEXT = '#EDEDED';
const FONT = 'file:///' + path.join(DIR, 'package/dist/fonts/geist-sans/Geist-Variable.ttf').replace(/\\/g, '/');

// The C monogram in a 180x180 box: an open ring (the C) around a play triangle.
const glyph = `<path d="M126 58 A48 48 0 1 0 126 122" fill="none" stroke="${ORANGE}" stroke-width="20" stroke-linecap="round"/>
  <path d="M82 72 L108 90 L82 108 Z" fill="${TEXT}"/>`;
// Rounded tile with the monogram (app icon look).
const tile = (r = 40) => `<rect width="180" height="180" rx="${r}" fill="${TILE}"/>${glyph}`;
const wordmark = (size) => `<span style="font: 800 ${size}px Geist; letter-spacing: ${-size * 0.04}px; color: ${TEXT}">Cox<span style="color: ${ORANGE}">TV</span></span>`;

function page(w, h, body, bg = 'transparent') {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
@font-face { font-family: Geist; src: url('${FONT}') format('truetype'); font-weight: 100 900; }
html, body { margin: 0; width: ${w}px; height: ${h}px; overflow: hidden; background: ${bg}; }
.c { width: ${w}px; height: ${h}px; display: flex; align-items: center; justify-content: center; }
svg { display: block; }
</style></head><body>${body}</body></html>`;
}

function render(name, w, h, html) {
  const file = path.join(HTML, name + '.html');
  fs.writeFileSync(file, html);
  const out = path.join(OUT, name + '.png');
  try { fs.unlinkSync(out); } catch {}
  execFileSync(CHROME, ['--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
    `--window-size=${w},${h}`, '--default-background-color=00000000', '--virtual-time-budget=2000',
    `--screenshot=${out}`, 'file:///' + file.replace(/\\/g, '/')], { stdio: 'ignore' });
  if (!fs.existsSync(out)) throw new Error('no output for ' + name);
  console.log('rendered', name, `${w}x${h}`);
}

// --- Android / Fire TV launcher icons ----------------------------------------------------
// Adaptive layers are 108dp; the glyph sits in the 66dp safe zone (about 58dp wide).
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
for (const [d, k] of Object.entries(densities)) {
  const L = Math.round(108 * k);
  render(`fg-${d}`, L, L, page(L, L, `<svg width="${L}" height="${L}" viewBox="0 0 108 108"><g transform="translate(54 54) scale(0.5) translate(-90 -90)">${glyph}</g></svg>`));
  render(`bg-${d}`, L, L, page(L, L, '', TILE));
  const S = Math.round(48 * k);
  render(`legacy-${d}`, S, S, page(S, S, `<svg width="${S}" height="${S}" viewBox="0 0 180 180">${tile(40)}</svg>`));
}

// --- Fire TV banner (16:9): monogram tile + wordmark on black ------------------------------
for (const [d, w] of Object.entries({ xhdpi: 320, xxhdpi: 480, xxxhdpi: 640 })) {
  const h = w * 9 / 16, u = w / 320;
  render(`banner-${d}`, w, h, page(w, h, `<div class="c" style="gap:${12 * u}px">
    <svg width="${64 * u}" height="${64 * u}" viewBox="0 0 180 180">${tile(40)}</svg>${wordmark(Math.round(46 * u))}</div>`, GROUND));
}

// --- Roku channel poster (focus icon) and splash ----------------------------------------
for (const [n, w, h] of [['roku-icon-fhd', 540, 405], ['roku-icon-hd', 290, 218]]) {
  const u = w / 540;
  render(n, w, h, page(w, h, `<div class="c" style="flex-direction:column;gap:${22 * u}px">
    <svg width="${150 * u}" height="${150 * u}" viewBox="0 0 180 180">${tile(40)}</svg>${wordmark(Math.round(84 * u))}</div>`, GROUND));
}
for (const [n, w, h] of [['roku-splash-fhd', 1920, 1080], ['roku-splash-hd', 1280, 720]]) {
  const u = w / 1920;
  render(n, w, h, page(w, h, `<div class="c" style="gap:${36 * u}px">
    <svg width="${200 * u}" height="${200 * u}" viewBox="0 0 180 180">${tile(40)}</svg>${wordmark(Math.round(150 * u))}</div>`, GROUND));
}

// --- Website: the stacked wordmark tile for the favicon and home-screen icons ------------
const stacked = (S, r) => `<svg width="${S}" height="${S}" viewBox="0 0 180 180">
  <rect width="180" height="180" rx="${r}" fill="${GROUND}"/>
  <text x="90" y="84" text-anchor="middle" font-family="Geist" font-weight="800" font-size="56" letter-spacing="-2.5" fill="${TEXT}">Cox</text>
  <text x="90" y="138" text-anchor="middle" font-family="Geist" font-weight="800" font-size="56" letter-spacing="-1" fill="${ORANGE}">TV</text></svg>`;
for (const [n, S, r] of [['favicon-32', 32, 36], ['favicon-48', 48, 36], ['apple-touch-icon', 180, 0], ['icon-192', 192, 0], ['icon-512', 512, 0]]) {
  render(n, S, S, page(S, S, stacked(S, r)));
}
// SVG favicon (fonts can't load inside a favicon, so the text is drawn with system fonts:
// Chrome/Edge/Firefox use it; the PNGs above are the exact versions).
fs.writeFileSync(path.join(OUT, 'favicon.svg'), `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 180 180">
<rect width="180" height="180" rx="36" fill="${GROUND}"/>
<text x="90" y="84" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-weight="900" font-size="56" letter-spacing="-2.5" fill="${TEXT}">Cox</text>
<text x="90" y="138" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif" font-weight="900" font-size="56" letter-spacing="-1" fill="${ORANGE}">TV</text>
</svg>
`);
// The monogram on its own, for the site header and anywhere else it's needed.
fs.writeFileSync(path.join(OUT, 'monogram.svg'), `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 180 180">\n${tile(40)}\n</svg>\n`);
console.log('done');

// Roku: the monogram for the in-app logo, and a white star (tinted orange or dark in the app).
render('roku-monogram', 96, 96, page(96, 96, `<svg width="96" height="96" viewBox="0 0 180 180">${tile(40)}</svg>`));
render('roku-star', 64, 64, page(64, 64, `<svg width="64" height="64" viewBox="0 0 24 24"><path d="M12 2.5l2.9 6.1 6.6.8-4.9 4.6 1.3 6.6L12 17.4l-5.9 3.2 1.3-6.6-4.9-4.6 6.6-.8z" fill="#FFFFFF"/></svg>`));
