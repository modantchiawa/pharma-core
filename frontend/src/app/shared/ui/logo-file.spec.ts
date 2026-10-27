import { describe, expect, it } from 'vitest';
import {
  ImageFileError,
  LOGO_MAX_BYTES,
  LogoFileError,
  fitWithin,
  needsRedraw,
  prepareLogo,
  prepareProductPhoto,
  squareCrop,
} from './logo-file';

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

describe('squareCrop', () => {
  it('keeps the centered square of a landscape or portrait photo', () => {
    expect(squareCrop(4032, 3024)).toEqual({ sx: 504, sy: 0, side: 3024 });
    expect(squareCrop(1080, 1920)).toEqual({ sx: 0, sy: 420, side: 1080 });
  });

  it('takes a square picture whole', () => {
    expect(squareCrop(256, 256)).toEqual({ sx: 0, sy: 0, side: 256 });
  });
});

describe('prepareProductPhoto', () => {
  it('explains how to get a JPEG instead of a HEIC photo', async () => {
    const heic = new File([new Uint8Array(10)], 'IMG_0002.heic', { type: 'image/heic' });
    await expect(prepareProductPhoto(heic)).rejects.toThrow(ImageFileError);
    await expect(prepareProductPhoto(heic)).rejects.toThrow(/compatible/);
  });

  it('refuses a document that is not a picture', async () => {
    const text = new File(['notice'], 'notice.txt', { type: 'text/plain' });
    await expect(prepareProductPhoto(text)).rejects.toThrow(/pas une image/);
  });
});
