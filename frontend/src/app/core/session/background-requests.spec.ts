import { describe, expect, it } from 'vitest';
import { isBackgroundRequest, problemCode } from './background-requests';

const API = 'http://localhost:8080/api';

describe('isBackgroundRequest', () => {
  it('marks what the terminal sends on its own', () => {
    expect(isBackgroundRequest('POST', `${API}/v1/pos/heartbeat`)).toBe(true);
    expect(isBackgroundRequest('POST', `${API}/v1/pos/sync/sales`)).toBe(true);
    expect(isBackgroundRequest('GET', `${API}/v1/pos/catalog?cursor=abc&limit=500`)).toBe(true);
    expect(isBackgroundRequest('GET', `${API}/v1/subscription/state`)).toBe(true);
    expect(isBackgroundRequest('GET', `${API}/v1/control/alerts/unread-count`)).toBe(true);
    expect(isBackgroundRequest('POST', `${API}/v1/me/session/lock`)).toBe(true);
    expect(isBackgroundRequest('POST', `${API}/v1/me/session-events`)).toBe(true);
  });

  it('leaves what the user caused as activity, the activity ping included', () => {
    expect(isBackgroundRequest('GET', `${API}/v1/inventory/stocks`)).toBe(false);
    expect(isBackgroundRequest('POST', `${API}/v1/sales`)).toBe(false);
    expect(isBackgroundRequest('POST', `${API}/v1/me/session/activity`)).toBe(false);
    expect(isBackgroundRequest('POST', `${API}/v1/subscription/refresh`)).toBe(false);
    expect(isBackgroundRequest('GET', `${API}/v1/control/alerts`)).toBe(false);
    expect(isBackgroundRequest('GET', `${API}/v1/pos/heartbeat`)).toBe(false);
  });
});

describe('problemCode', () => {
  it('reads the code of a problem detail', () => {
    expect(problemCode({ code: 'session-locked', status: 423 })).toBe('session-locked');
    expect(problemCode(null)).toBeNull();
    expect(problemCode('Bad Gateway')).toBeNull();
    expect(problemCode({ code: 423 })).toBeNull();
  });
});
