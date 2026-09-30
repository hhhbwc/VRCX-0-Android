/* VRCX-0 · 极简 app-shell 缓存，让 PWA 可离线 / 秒开 */
const CACHE = 'vrcx0-tablet-v1';
const ASSETS = [
  './',
  './index.html',
  './styles/tokens.css',
  './styles/stage.css',
  './styles/app.css',
  './styles/brand.css',
  './scripts/main.js'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if (url.origin !== self.location.origin) return;
  e.respondWith(
    caches.match(e.request).then((cached) => {
      if (cached) return cached;
      return fetch(e.request).then((resp) => {
        if (resp && resp.ok) {
          const cp = resp.clone();
          caches.open(CACHE).then((c) => c.put(e.request, cp));
        }
        return resp;
      }).catch(() => caches.match('./'));
    })
  );
});
