/** Header telling the server a request is not the user's activity (LockedRequestPolicy). */
export const BACKGROUND_HEADER = 'X-PharmaCore-Background';

/**
 * Requests the terminal sends on its own: polls, heartbeat, offline replay, catalog refresh,
 * the session's own lock and journal. They must not keep an unattended session open, so they
 * are marked; anything else the user caused, and counts as activity on the server.
 */
const BACKGROUND: { method: string; path: RegExp }[] = [
  { method: 'POST', path: /\/v1\/pos\/heartbeat$/ },
  { method: 'POST', path: /\/v1\/pos\/sync\/sales$/ },
  { method: 'GET', path: /\/v1\/pos\/catalog$/ },
  { method: 'GET', path: /\/v1\/pos\/terminals$/ },
  { method: 'GET', path: /\/v1\/hub\/sync$/ },
  { method: 'GET', path: /\/v1\/subscription\/state$/ },
  { method: 'GET', path: /\/v1\/control\/alerts\/unread-count$/ },
  { method: 'POST', path: /\/v1\/me\/session-events$/ },
  { method: 'POST', path: /\/v1\/me\/session\/lock$/ },
];

export function isBackgroundRequest(method: string, url: string): boolean {
  const path = url.split('?')[0];
  return BACKGROUND.some((entry) => entry.method === method && entry.path.test(path));
}

/** The `code` of a problem detail returned by the server, if any. */
export function problemCode(body: unknown): string | null {
  const code = (body as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : null;
}
