import { describe, expect, it } from 'vitest';
import { galenicShape, productImagePath, thumbHue, thumbInitial } from './product-thumb';

describe('galenicShape', () => {
  it('reads the catalog form first', () => {
    expect(galenicShape('XYZ 10', 'Gélule')).toBe('capsule');
    expect(galenicShape('XYZ 10', 'Comprimé')).toBe('tablet');
    expect(galenicShape('XYZ 10', 'Gel')).toBe('topical');
    expect(galenicShape('XYZ 10', 'Aérosol')).toBe('inhaler');
    expect(galenicShape('XYZ 10', 'Injectable')).toBe('injection');
    expect(galenicShape('XYZ 10', 'Accessoire')).toBe('device');
  });

  it('falls back on the abbreviations of the designation', () => {
    expect(galenicShape('PARACETAMOL 500MG CPR B/16')).toBe('tablet');
    expect(galenicShape('AMOXICILLINE 500MG GEL B/12')).toBe('capsule');
    expect(galenicShape('DICLOFENAC GEL 1% 50G')).toBe('topical');
    expect(galenicShape('EFFERALGAN PEDIATRIQUE SIROP 90ML')).toBe('liquid');
    expect(galenicShape('INSULATARD 100UI/ML FL 10ML')).toBe('injection');
    expect(galenicShape('SALBUTAMOL 100µG AEROSOL')).toBe('inhaler');
    expect(galenicShape('SRO SACHETS B/10')).toBe('sachet');
    expect(galenicShape('MOUSTIQUAIRE IMPREGNEE 2 PLACES')).toBe('device');
  });

  it('gives nothing when neither tells the form', () => {
    expect(galenicShape('PRODUIT INCONNU', null)).toBeNull();
    expect(galenicShape('PRODUIT INCONNU', 'Autre')).toBeNull();
  });
});

describe('thumbHue', () => {
  it('is stable for a name and spread across names', () => {
    expect(thumbHue('DOLIPRANE 1000MG')).toBe(thumbHue(' doliprane 1000mg '));
    const hues = new Set(
      ['PARACETAMOL', 'AMOXICILLINE', 'METFORMINE', 'IBUPROFENE', 'ZINC'].map(thumbHue),
    );
    expect(hues.size).toBeGreaterThan(3);
    for (const hue of hues) {
      expect(hue).toBeGreaterThanOrEqual(0);
      expect(hue).toBeLessThan(360);
    }
  });
});

describe('thumbInitial', () => {
  it('takes the first letter, without accent, skipping digits', () => {
    expect(thumbInitial('amoxicilline')).toBe('A');
    expect(thumbInitial('Éfferalgan')).toBe('E');
    expect(thumbInitial('3TC 150MG')).toBe('T');
    expect(thumbInitial('100')).toBe('·');
  });
});

describe('productImagePath', () => {
  it('carries the version so the URL never changes content', () => {
    expect(productImagePath('p-1', '0123abcd')).toBe('/v1/inventory/products/p-1/image?v=0123abcd');
  });
});
