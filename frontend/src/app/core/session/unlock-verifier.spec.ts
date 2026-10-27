import { beforeEach, describe, expect, it } from 'vitest';
import { UnlockVerifier, createVerifier, matchesVerifier } from './unlock-verifier';

describe('verifiers', () => {
  it('match the secret they were made from, salted differently each time', async () => {
    const first = await createVerifier('1234', 1_000);
    const second = await createVerifier('1234', 1_000);
    expect(first.hash).not.toBe(second.hash);
    expect(first.hash).not.toContain('1234');
    expect(await matchesVerifier('1234', first)).toBe(true);
    expect(await matchesVerifier('1235', first)).toBe(false);
  });
});

describe('UnlockVerifier', () => {
  let now = Date.UTC(2026, 9, 27, 8);

  beforeEach(() => {
    localStorage.clear();
    now = Date.UTC(2026, 9, 27, 8);
  });

  it('unlocks offline with the PIN or the password of the signed-in user only', async () => {
    const verifier = new UnlockVerifier(1_000, () => now);
    await verifier.remember('user-a', 'password', 'Demo2026!');
    await verifier.remember('user-a', 'pin', '4321');
    expect(await verifier.verify('user-a', '4321')).toBe(true);
    expect(await verifier.verify('user-a', 'Demo2026!')).toBe(true);
    expect(await verifier.verify('user-a', '0000')).toBe(false);
    expect(await verifier.verify('user-b', '4321')).toBe(false);
    expect(localStorage.getItem('pharmacore.unlock')).not.toContain('4321');
  });

  it('forgets the previous user and expires with the refresh session', async () => {
    const verifier = new UnlockVerifier(1_000, () => now);
    await verifier.remember('user-a', 'pin', '4321');
    await verifier.remember('user-b', 'pin', '9999');
    expect(await verifier.verify('user-a', '4321')).toBe(false);
    expect(verifier.available('user-b')).toBe(true);
    now += 15 * 3_600_000;
    expect(await verifier.verify('user-b', '9999')).toBe(false);
    verifier.clear();
    expect(verifier.available('user-b')).toBe(false);
  });
});
