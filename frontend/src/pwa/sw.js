/*
 * Service worker = Angular's ngsw (offline app shell, asset caching, updates)
 * + Background Sync: when the browser regains connectivity it wakes this worker, which asks
 * the open POS windows to flush their IndexedDB sale journal (the access token never leaves
 * the page, so the worker does not call the API itself).
 */
importScripts('./ngsw-worker.js');

self.addEventListener('sync', (event) => {
  if (event.tag === 'pharmacore-outbox') {
    event.waitUntil(
      self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
        clients.forEach((client) => client.postMessage({ type: 'flush-outbox' }));
      }),
    );
  }
});
