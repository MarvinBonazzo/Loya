import { readFile, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const webRoot = join(repositoryRoot, 'app', 'src', 'main', 'assets');
const manifestPath = join(webRoot, 'manifest.webmanifest');
const serviceWorkerPath = join(webRoot, 'sw.js');

const errors = [];
const check = (condition, message) => {
  if (!condition) errors.push(message);
};

let manifest;
try {
  manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
} catch (error) {
  errors.push(`manifest.webmanifest is not valid JSON: ${error.message}`);
  manifest = {};
}

check(manifest.id === './', 'manifest id must be "./" for project-safe GitHub Pages installs');
check(manifest.start_url === './', 'manifest start_url must be "./"');
check(manifest.scope === './', 'manifest scope must be "./"');
check(manifest.display === 'standalone', 'manifest display must be "standalone"');
check(manifest.lang === 'it', 'Italian must remain the default manifest language');
check(Array.isArray(manifest.icons), 'manifest icons must be an array');

const iconEntries = Array.isArray(manifest.icons) ? manifest.icons : [];
const pngIcons = iconEntries.filter(icon => icon.type === 'image/png');
const maskableIcon = pngIcons.find(icon => String(icon.purpose).split(/\s+/).includes('maskable'));
check(pngIcons.some(icon => icon.sizes === '192x192'), 'manifest needs a 192x192 PNG icon');
check(pngIcons.some(icon => icon.sizes === '512x512'), 'manifest needs a 512x512 PNG icon');
check(Boolean(maskableIcon), 'manifest needs a maskable PNG icon');

async function readPngSize(path) {
  const bytes = await readFile(path);
  const signature = '89504e470d0a1a0a';
  if (bytes.length < 24 || bytes.subarray(0, 8).toString('hex') !== signature) {
    throw new Error('not a PNG file');
  }
  return `${bytes.readUInt32BE(16)}x${bytes.readUInt32BE(20)}`;
}

for (const icon of pngIcons) {
  const relativePath = String(icon.src || '');
  check(relativePath.startsWith('./icons/'), `icon path must be relative: ${relativePath}`);
  if (!relativePath.startsWith('./')) continue;
  const absolutePath = join(webRoot, relativePath.slice(2));
  try {
    const actualSize = await readPngSize(absolutePath);
    check(actualSize === icon.sizes, `${relativePath} is ${actualSize}, expected ${icon.sizes}`);
  } catch (error) {
    errors.push(`cannot validate ${relativePath}: ${error.message}`);
  }
}

const serviceWorker = await readFile(serviceWorkerPath, 'utf8');
const shellBlock = serviceWorker.match(/const APP_SHELL = Object\.freeze\(\[([\s\S]*?)\]\);/);
check(Boolean(shellBlock), 'service worker APP_SHELL list was not found');

const shellAssets = shellBlock
  ? [...shellBlock[1].matchAll(/['"](\.\/[^'"]*)['"]/g)].map(match => match[1])
  : [];

const requiredShellAssets = [
  './',
  './index.html',
  './manifest.webmanifest',
  './css/style.css',
  './icons/icon.svg',
  './icons/icon-180.png',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './js/i18n.js',
  './js/app.js',
  './js/bwip-js-min.js',
  './js/jsbarcode.min.js',
  './js/qrcode.min.js',
];

check(new Set(shellAssets).size === shellAssets.length, 'service worker APP_SHELL contains duplicates');
for (const required of requiredShellAssets) {
  check(shellAssets.includes(required), `service worker APP_SHELL is missing ${required}`);
}

for (const asset of shellAssets) {
  check(asset.startsWith('./'), `service worker asset must be relative: ${asset}`);
  check(!asset.includes('://'), `service worker asset must be local: ${asset}`);
  const assetPath = asset === './' ? join(webRoot, 'index.html') : join(webRoot, asset.slice(2));
  try {
    check((await stat(assetPath)).isFile(), `service worker asset is not a file: ${asset}`);
  } catch {
    errors.push(`service worker asset does not exist: ${asset}`);
  }
}

for (const eventName of ['install', 'activate', 'fetch']) {
  check(
    serviceWorker.includes(`addEventListener('${eventName}'`),
    `service worker is missing its ${eventName} handler`,
  );
}

if (errors.length) {
  console.error('PWA validation failed:');
  errors.forEach(error => console.error(`- ${error}`));
  process.exitCode = 1;
} else {
  console.log(`PWA validation passed: ${shellAssets.length} offline assets and ${pngIcons.length} PNG icons.`);
}
