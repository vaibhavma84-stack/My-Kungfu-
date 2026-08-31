// Caches the whole app on first visit so it opens with no signal afterwards.
// Bump CACHE whenever index.html changes — the old cache is then discarded.
const CACHE = 'gasplanet-deck-log-v45';
const ASSETS = [
  './', './index.html', './manifest.webmanifest',
  './icon-180.png', './icon-192.png', './icon-512.png'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;

  // Page loads answer from cache first, so the app opens instantly at sea even
  // when the radio is up but there is no usable link. A fresh copy is fetched
  // in the background and picked up on the next launch.
  if (req.mode === 'navigate') {
    event.respondWith(
      caches.match('./index.html').then(cached => {
        const fromNetwork = fetch(req).then(res => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(CACHE).then(cache => cache.put('./index.html', copy));
          }
          return res;
        }).catch(() => null);
        return cached || fromNetwork.then(res => res || new Response(
          '<h1>Offline</h1><p>Open this page once while connected, then it works without a signal.</p>',
          { status: 503, headers: { 'Content-Type': 'text/html' } }
        ));
      })
    );
    return;
  }

  event.respondWith(
    caches.match(req).then(cached => cached || fetch(req).then(res => {
      if (res && res.ok && new URL(req.url).origin === self.location.origin) {
        const copy = res.clone();
        caches.open(CACHE).then(cache => cache.put(req, copy));
      }
      return res;
    }))
  );
});
