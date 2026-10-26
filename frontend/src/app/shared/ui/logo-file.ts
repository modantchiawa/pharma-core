/** Server limits (BrandingService.MAX_LOGO_BYTES, PNG/JPEG/WebP only). */
export const LOGO_MAX_BYTES = 512 * 1024;
/** Longest side kept for the logo: sharp in the sidebar, the login screen and on 80 mm tickets. */
export const LOGO_MAX_SIDE = 512;
const UPLOADABLE = ['image/png', 'image/jpeg', 'image/webp'];

/** A logo the pharmacy cannot use as is, with the reason in plain French. */
export class LogoFileError extends Error {}

/** Scales down to fit a square of `max` pixels, keeping the ratio; never enlarges. */
export function fitWithin(
  width: number,
  height: number,
  max: number,
): { width: number; height: number } {
  if (width <= 0 || height <= 0) {
    return { width: max, height: max };
  }
  const scale = Math.min(1, max / Math.max(width, height));
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  };
}

/** Upload as is when the server accepts the file and it is not oversized; otherwise redraw. */
export function needsRedraw(type: string, bytes: number, width: number, height: number): boolean {
  return (
    !UPLOADABLE.includes(type) ||
    bytes > LOGO_MAX_BYTES ||
    Math.max(width, height) > LOGO_MAX_SIDE * 2
  );
}

/**
 * Turns whatever the pharmacist picks (phone photo, large PNG, SVG from the designer) into a
 * file the server accepts: PNG/JPEG/WebP under 512 KB. SVG is rasterized in the browser (the
 * server refuses SVG, which can carry scripts); large images are scaled down to 512 px, as PNG
 * to keep transparency, then WebP when the PNG is still too heavy.
 */
export async function prepareLogo(file: File): Promise<Blob> {
  if (/hei[cf]$/i.test(file.type) || /\.hei[cf]$/i.test(file.name)) {
    throw new LogoFileError('Format HEIC non pris en charge : enregistrez le logo en PNG ou JPEG');
  }
  if (!file.type.startsWith('image/')) {
    throw new LogoFileError('Ce fichier n’est pas une image (PNG, JPEG, WebP ou SVG)');
  }
  const image = await decode(file);
  if (!needsRedraw(file.type, file.size, image.naturalWidth, image.naturalHeight)) {
    return file;
  }
  const size = fitWithin(image.naturalWidth, image.naturalHeight, LOGO_MAX_SIDE);
  const canvas = document.createElement('canvas');
  canvas.width = size.width;
  canvas.height = size.height;
  const context = canvas.getContext('2d');
  if (!context) {
    throw new LogoFileError('Ce navigateur ne sait pas redimensionner l’image');
  }
  context.imageSmoothingQuality = 'high';
  context.drawImage(image, 0, 0, size.width, size.height);
  for (const [type, quality] of [
    ['image/png', undefined],
    ['image/webp', 0.9],
    ['image/webp', 0.75],
  ] as const) {
    const blob = await encode(canvas, type, quality);
    if (blob && blob.size <= LOGO_MAX_BYTES && UPLOADABLE.includes(blob.type)) {
      return blob;
    }
  }
  throw new LogoFileError('Image trop lourde même réduite : simplifiez le logo (512 Ko max)');
}

function decode(file: File): Promise<HTMLImageElement> {
  const url = URL.createObjectURL(file);
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new LogoFileError('Image illisible ou endommagée'));
    image.src = url;
  }).finally(() => URL.revokeObjectURL(url));
}

function encode(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}
