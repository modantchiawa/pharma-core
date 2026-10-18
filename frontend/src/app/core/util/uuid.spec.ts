import { uuidv7 } from './uuid';

describe('uuidv7', () => {
  it('encodes version, variant and timestamp', () => {
    const id = uuidv7(0x0189_1234_5678, (bytes) => bytes.fill(0xff));

    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(id.startsWith('01891234-5678-7')).toBe(true);
  });

  it('is time ordered', () => {
    const earlier = uuidv7(1_700_000_000_000);
    const later = uuidv7(1_700_000_000_001);

    expect(earlier < later).toBe(true);
  });
});
