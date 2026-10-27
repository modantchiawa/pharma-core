/** Server limits (BrandingService.MAX_LOGO_BYTES, PNG/JPEG/WebP only). */
export const LOGO_MAX_BYTES = 512 * 1024;
/** Longest side kept for the logo: sharp in the sidebar, the login screen and on 80 mm tickets. */
export const LOGO_MAX_SIDE = 512;
const UPLOADABLE = ['image/png', 'image/jpeg', 'image/webp'];

/** Side of a product photo once cropped: sharp in the product sheet, light to replicate. */
export const PHOTO_SIDE = 256;
/** Server limit of a product photo (ProductImageRules.MAX_BYTES). */
export const PHOTO_MAX_BYTES = 256 * 1024;

/** An image the pharmacy cannot use as is, with the reason in plain French. */
export class ImageFileError extends Error {}

/** A logo the pharmacy cannot use as is. */
export class LogoFileError extends ImageFileError {}

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

/** Largest centered square of a picture, as the source rectangle to draw from. */
export function squareCrop(
  width: number,
  height: number,
): { sx: number; sy: number; side: number } {
  const side = Math.max(1, Math.min(width, height));
  return {
    sx: Math.max(0, Math.round((width - side) / 2)),
    sy: Math.max(0, Math.round((height - side) / 2)),
    side,
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
  if (isHeic(file)) {
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

/**
 * Turns a photo of a box (phone camera, scan, picture from the supplier) into the product's
 * thumbnail: the centered square, reduced to 256 px, as WebP at 80 % (JPEG where the browser
 * cannot encode WebP). A few tens of kilobytes, so it replicates and caches well.
 */
export async function prepareProductPhoto(file: File): Promise<Blob> {
  if (isHeic(file)) {
    throw new ImageFileError(
      'Format HEIC non pris en charge : réglez l’appareil photo sur « Le plus compatible » (JPEG)',
    );
  }
  if (!file.type.startsWith('image/')) {
    throw new ImageFileError('Ce fichier n’est pas une image (JPEG, PNG ou WebP)');
  }
  const image = await decode(file, ImageFileError);
  const crop = squareCrop(image.naturalWidth, image.naturalHeight);
  const side = Math.min(PHOTO_SIDE, crop.side);
  const canvas = document.createElement('canvas');
  canvas.width = side;
  canvas.height = side;
  const context = canvas.getContext('2d');
  if (!context) {
    throw new ImageFileError('Ce navigateur ne sait pas redimensionner l’image');
  }
  // White behind transparent pictures: JPEG has no transparency
  context.fillStyle = '#ffffff';
  context.fillRect(0, 0, side, side);
  context.imageSmoothingQuality = 'high';
  context.drawImage(image, crop.sx, crop.sy, crop.side, crop.side, 0, 0, side, side);
  for (const type of ['image/webp', 'image/jpeg']) {
    const blob = await encode(canvas, type, 0.8);
    if (blob && blob.type === type && blob.size <= PHOTO_MAX_BYTES) {
      return blob;
    }
  }
  throw new ImageFileError('Photo impossible à réduire : essayez une autre image');
}

function isHeic(file: File): boolean {
  return /hei[cf]$/i.test(file.type) || /\.hei[cf]$/i.test(file.name);
}

function decode(
  file: File,
  failure: new (message: string) => ImageFileError = LogoFileError,
): Promise<HTMLImageElement> {
  const url = URL.createObjectURL(file);
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new failure('Image illisible ou endommagée'));
    image.src = url;
  }).finally(() => URL.revokeObjectURL(url));
}

function encode(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}
