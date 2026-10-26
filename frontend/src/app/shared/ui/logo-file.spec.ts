import { describe, expect, it } from 'vitest';
import { LOGO_MAX_BYTES, LogoFileError, fitWithin, needsRedraw, prepareLogo } from './logo-file';

describe('fitWithin', () => {
  it('scales the longest side down to the limit and keeps the ratio', () => {
    expect(fitWithin(2000, 1000, 512)).toEqual({ width: 512, height: 256 });
    expect(fitWithin(300, 1200, 512)).toEqual({ width: 128, height: 512 });
  });

  it('never enlarges a small logo', () => {
    expect(fitWithin(120, 80, 512)).toEqual({ width: 120, height: 80 });
  });

  it('gives a square to an SVG without intrinsic size', () => {
    expect(fitWithin(0, 0, 512)).toEqual({ width: 512, height: 512 });
  });
});

describe('needsRedraw', () => {
  it('uploads a light PNG, JPEG or WebP as is', () => {
    expect(needsRedraw('image/png', 80_000, 400, 400)).toBe(false);
    expect(needsRedraw('image/jpeg', 200_000, 1024, 768)).toBe(false);
    expect(needsRedraw('image/webp', 30_000, 512, 512)).toBe(false);
  });

  it('redraws SVG, GIF, heavy files and very large pictures', () => {
    expect(needsRedraw('image/svg+xml', 4_000, 0, 0)).toBe(true);
    expect(needsRedraw('image/gif', 10_000, 100, 100)).toBe(true);
    expect(needsRedraw('image/png', LOGO_MAX_BYTES + 1, 400, 400)).toBe(true);
    expect(needsRedraw('image/jpeg', 300_000, 4032, 3024)).toBe(true);
  });
});

describe('prepareLogo', () => {
  it('explains that iPhone HEIC photos must be converted', async () => {
    const heic = new File([new Uint8Array(10)], 'IMG_0001.HEIC', { type: '' });
    await expect(prepareLogo(heic)).rejects.toThrow(LogoFileError);
    await expect(prepareLogo(heic)).rejects.toThrow(/HEIC/);
  });

  it('refuses a file that is not an image', async () => {
    const pdf = new File(['%PDF-1.7'], 'logo.pdf', { type: 'application/pdf' });
    await expect(prepareLogo(pdf)).rejects.toThrow(/pas une image/);
  });
});
