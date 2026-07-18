'use strict';

const CACHE_PREFIX = 'loya-app-shell-';
const LEGACY_CACHE_PREFIXES = Object.freeze(['carta-wallet-app-shell-']);
const CACHE_NAME = `${CACHE_PREFIX}v6`;

// Keep every entry relative so the same files work at a custom domain and at
// https://owner.github.io/repository/ without knowing the repository name.
const APP_SHELL = Object.freeze([
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
]);

const scopedUrl = path => new URL(path, self.registration.scope).href;
const INDEX_URL = scopedUrl('./index.html');

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(APP_SHELL.map(scopedUrl))),
  );
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(names => Promise.all(
      names
        .filter(name => (
          name.startsWith(CACHE_PREFIX)
          || LEGACY_CACHE_PREFIXES.some(prefix => name.startsWith(prefix))
        ) && name !== CACHE_NAME)
        .map(name => caches.delete(name)),
    )),
  );
  self.clients.claim();
});

self.addEventListener('fetch', event => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const requestUrl = new URL(request.url);
  if (requestUrl.origin !== self.location.origin) return;

  if (request.mode === 'navigate') {
    event.respondWith(networkFirstNavigation(request));
    return;
  }

  event.respondWith(staleWhileRevalidate(request, event));
});

async function networkFirstNavigation(request) {
  try {
    const response = await fetch(request);
    if (!response.ok) throw new Error(`Navigation failed with ${response.status}`);
    const cache = await caches.open(CACHE_NAME);
    await cache.put(INDEX_URL, response.clone());
    return response;
  } catch (_) {
    return (await caches.match(INDEX_URL)) || Response.error();
  }
}

async function staleWhileRevalidate(request, event) {
  const cached = await caches.match(request);
  const refresh = fetch(request)
    .then(async response => {
      if (response.ok && response.type === 'basic') {
        const cache = await caches.open(CACHE_NAME);
        await cache.put(request, response.clone());
      }
      return response;
    })
    .catch(() => null);

  if (cached) {
    event.waitUntil(refresh);
    return cached;
  }

  return (await refresh) || Response.error();
}
