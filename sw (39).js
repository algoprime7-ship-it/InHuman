// InHuman AI — makes the app / PWA open fast.
// • The page itself: network first (so updates show straight away), but if the network is slow (>3 s) the saved copy opens.
// • 3D + style libraries from the CDNs: saved on the phone after the first visit, so they don't download every time.
// • Voice clips and sound effects: saved after the first play.
// Never touches /api (AI, credits, accounts) or other sites (YouTube, PayPal, fonts).
const CACHE = 'inhuman-v2';
const LIBS = 'inhuman-libs-v1';
const SHELL = ['/', '/manifest.json', '/icon-192.png', '/icon-512.png', '/icon-180.png'];
const LIB_HOSTS = ['cdnjs.cloudflare.com', 'cdn.jsdelivr.net', 'cdn.tailwindcss.com'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).catch(() => {}));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE && k !== LIBS).map(k => caches.delete(k)))));
  self.clients.claim();
});

function saveTo(name, req, res) {
  if (res && (res.ok || res.type === 'opaque')) { const copy = res.clone(); caches.open(name).then(c => c.put(req, copy)).catch(() => {}); }
  return res;
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // libraries: saved copy first (they never change at these versions); Tailwind refreshes quietly in the background
  if (LIB_HOSTS.includes(url.hostname)) {
    e.respondWith(caches.open(LIBS).then(c => c.match(req).then(hit => {
      const net = fetch(req).then(res => saveTo(LIBS, req, res)).catch(() => hit);
      if (hit) { if (url.hostname === 'cdn.tailwindcss.com') e.waitUntil(net); return hit; }
      return net;
    })));
    return;
  }

  if (url.origin !== location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/admin')) return;

  // voice clips / sound effects: saved after the first play
  if (url.pathname.startsWith('/voices/') || url.pathname.startsWith('/sounds/')) {
    e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => saveTo(CACHE, req, res))));
    return;
  }

  // the page: fresh from the network, but don't wait more than 3 s on a slow connection
  const key = req.mode === 'navigate' ? '/' : req;
  e.respondWith(new Promise(resolve => {
    let done = false;
    const finish = r => { if (!done && r) { done = true; resolve(r); } };
    const net = fetch(req).then(res => { saveTo(CACHE, key, res); finish(res); return res; });
    const timer = setTimeout(() => caches.match(key).then(finish), 3000);
    net.catch(() => caches.match(key).then(r => finish(r || new Response('Offline', { status: 503 })))).finally(() => clearTimeout(timer));
  }));
});
