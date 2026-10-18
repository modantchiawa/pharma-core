/**
 * RFC 9562 UUID version 7: 48-bit Unix timestamp in milliseconds followed by random bits.
 * Time-ordered identifiers keep the server indexes compact and give a natural replay order.
 */
export function uuidv7(
  now: number = Date.now(),
  random: (bytes: Uint8Array<ArrayBuffer>) => Uint8Array<ArrayBuffer> = fillRandom,
): string {
  const bytes = random(new Uint8Array(16));
  let timestamp = now;
  for (let i = 5; i >= 0; i--) {
    bytes[i] = timestamp & 0xff;
    timestamp = Math.floor(timestamp / 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function fillRandom(bytes: Uint8Array<ArrayBuffer>): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(bytes);
}
