const STORAGE_KEY = 'pharmacore.unlock';
const ITERATIONS = 150_000;
/** As long as the refresh session: past it, the terminal needs the server anyway. */
const LIFETIME_MS = 14 * 3_600_000;

export type SecretKind = 'pin' | 'password';

/** Salted PBKDF2 digest of a secret: never the secret itself. */
export interface Verifier {
  salt: string;
  hash: string;
  iterations: number;
}

interface StoredVerifiers {
  userId: string;
  expiresAt: number;
  secrets: Partial<Record<SecretKind, Verifier>>;
}

export async function createVerifier(secret: string, iterations = ITERATIONS): Promise<Verifier> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return { salt: toBase64(salt), hash: await derive(secret, salt, iterations), iterations };
}

export async function matchesVerifier(secret: string, verifier: Verifier): Promise<boolean> {
  const candidate = await derive(secret, fromBase64(verifier.salt), verifier.iterations);
  return constantTimeEqual(candidate, verifier.hash);
}

/**
 * Unlocking the screen without the server. The PIN and password the user typed successfully
 * on this terminal are kept as salted PBKDF2 digests (never in clear), for this user only, as
 * long as the refresh session. When the server cannot be reached the lock screen checks the
 * typed secret against them; online, the server always decides (and audits).
 */
export class UnlockVerifier {
  constructor(
    private readonly iterations = ITERATIONS,
    private readonly now: () => number = Date.now,
  ) {}

  async remember(userId: string, kind: SecretKind, secret: string): Promise<void> {
    const verifier = await createVerifier(secret, this.iterations);
    const current = this.read();
    const secrets = current?.userId === userId ? current.secrets : {};
    this.write({
      userId,
      expiresAt: this.now() + LIFETIME_MS,
      secrets: { ...secrets, [kind]: verifier },
    });
  }

  async verify(userId: string, secret: string): Promise<boolean> {
    const stored = this.read();
    if (!stored || stored.userId !== userId || stored.expiresAt <= this.now()) {
      return false;
    }
    for (const verifier of Object.values(stored.secrets)) {
      if (verifier && (await matchesVerifier(secret, verifier))) {
        return true;
      }
    }
    return false;
  }

  /** Whether an offline unlock is possible for this user right now. */
  available(userId: string): boolean {
    const stored = this.read();
    return !!stored && stored.userId === userId && stored.expiresAt > this.now();
  }

  clear(): void {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // nothing stored
    }
  }

  private read(): StoredVerifiers | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? (JSON.parse(raw) as StoredVerifiers) : null;
    } catch {
      return null;
    }
  }

  private write(value: StoredVerifiers): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
    } catch {
      // Storage full: offline unlock is not available, online unlock still is
    }
  }
}

async function derive(secret: string, salt: Uint8Array, iterations: number): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    'PBKDF2',
    false,
    ['deriveBits'],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations },
    key,
    256,
  );
  return toBase64(new Uint8Array(bits));
}

function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let difference = 0;
  for (let i = 0; i < a.length; i++) {
    difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return difference === 0;
}

function toBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

function fromBase64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
}
