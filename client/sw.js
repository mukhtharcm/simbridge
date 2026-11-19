// Keeps the app's own files so it opens without a network. It never touches the connection to the
// phone, and it prefers the network, so a new version is picked up on the next open.
const SHELL = 'simbridge-shell-3';
const FILES = ['./', 'index.html', 'manifest.json', 'icon-192.png', 'icon-512.png', 'bricolage-grotesque.woff2'];

self.addEventListener('install', (e) => e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).then(() => self.skipWaiting())));
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET' || new URL(e.request.url).origin !== location.origin) return;
  e.respondWith(
    fetch(e.request)
      .then((res) => { const copy = res.clone(); caches.open(SHELL).then((c) => c.put(e.request, copy)); return res; })
      .catch(() => caches.match(e.request, { ignoreSearch: true })),
  );
});

// A call or a text while the app is closed. Every push shows a notification: browsers require it.
self.addEventListener('push', (e) => {
  let d = {};
  try { d = e.data?.json() ?? {}; } catch {}
  e.waitUntil(self.registration.showNotification(d.title || 'simbridge', {
    body: d.body || '', tag: d.tag || 'simbridge', renotify: true,
    icon: 'icon-192.png', badge: 'icon-192.png', requireInteraction: !!d.sticky,
  }));
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((wins) => {
    const win = wins.find((w) => new URL(w.url).origin === location.origin);
    return win ? win.focus() : self.clients.openWindow('./');
  }));
});
