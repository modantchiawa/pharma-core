/** Dosage form drawn in the placeholder of a product without photo. */
export type GalenicShape =
  'tablet' | 'capsule' | 'liquid' | 'injection' | 'inhaler' | 'topical' | 'sachet' | 'device';

const DESIGNATION_RULES: { shape: GalenicShape; pattern: RegExp }[] = [
  { shape: 'injection', pattern: /\b(INJ|INJECTABLE|AMP|AMPOULES?|SER|SERINGUE|VACCIN)\b|UI\/ML/ },
  { shape: 'inhaler', pattern: /\b(AEROSOL|INHAL|INHALATION|SPRAY|NEBUL)/ },
  {
    shape: 'liquid',
    pattern: /\b(SIROP|SIR|SUSP|SUSPENSION|SOL|SOLUTION|BUV|BUVABLE|COLLYRE|GTTES|GOUTTES|FL)\b/,
  },
  { shape: 'sachet', pattern: /\b(SACHETS?|SACH|PDRE|POUDRE)\b/ },
  {
    shape: 'topical',
    pattern: /\b(POMMADE|POM|CREME|CR|BAUME|LOTION|TUBE)\b|\bGEL\b(?!.*\bB\/\d)/,
  },
  { shape: 'capsule', pattern: /\b(GEL|GELULES?|CAPS|CAPSULES?)\b/ },
  { shape: 'tablet', pattern: /\b(CPR|COMPRIMES?|CP|DRAGEES?|LYOC|OVULES?|SUPPO)\b/ },
  {
    shape: 'device',
    pattern: /\b(MOUSTIQUAIRE|THERMOMETRE|SERINGUES|PANSEMENTS?|COMPRESSES?|TEST)\b/,
  },
];

const FORM_RULES: { shape: GalenicShape; pattern: RegExp }[] = [
  { shape: 'injection', pattern: /INJECT/ },
  { shape: 'inhaler', pattern: /AEROSOL|INHAL/ },
  { shape: 'liquid', pattern: /SIROP|SOLUTION|SUSPENSION|GOUTTE|COLLYRE/ },
  { shape: 'sachet', pattern: /SACHET|POUDRE/ },
  { shape: 'topical', pattern: /^GEL$|POMMADE|CREME|LOTION/ },
  { shape: 'capsule', pattern: /GELULE|CAPSULE/ },
  { shape: 'tablet', pattern: /COMPRIM|DRAGEE|SUPPO|OVULE/ },
  { shape: 'device', pattern: /ACCESSOIRE|DISPOSITIF/ },
];

/**
 * Dosage form of a product, from its catalog form when known ("Gélule", "Sirop") or else from
 * the abbreviations of its designation ("AMOXICILLINE 500MG GEL B/12"). A "GEL" followed by a
 * box size is a capsule; otherwise it is a gel for the skin.
 */
export function galenicShape(designation: string, form?: string | null): GalenicShape | null {
  const byForm = form ? match(FORM_RULES, plain(form).trim()) : null;
  return byForm ?? match(DESIGNATION_RULES, plain(designation));
}

function match(
  rules: { shape: GalenicShape; pattern: RegExp }[],
  text: string,
): GalenicShape | null {
  return rules.find((rule) => rule.pattern.test(text))?.shape ?? null;
}

function plain(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toUpperCase();
}

/** Stable hue (0-359) of a product name: the same product always gets the same tint. */
export function thumbHue(name: string): number {
  let hash = 0x811c9dc5;
  for (const char of name.trim().toUpperCase()) {
    hash ^= char.codePointAt(0) ?? 0;
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0) % 360;
}

/** First letter of the name, skipping digits and punctuation ("3TC 150MG" gives "T"). */
export function thumbInitial(name: string): string {
  const letter = name
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .match(/\p{L}/u);
  return letter ? letter[0].toUpperCase() : '·';
}

/** URL of a product photo: its version makes it immutable, so the browser keeps it for good. */
export function productImagePath(productId: string, version: string): string {
  return `/v1/inventory/products/${encodeURIComponent(productId)}/image?v=${encodeURIComponent(version)}`;
}
