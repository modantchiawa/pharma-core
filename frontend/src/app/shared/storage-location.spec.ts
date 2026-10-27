import { describe, expect, it } from 'vitest';
import { StorageLocation } from '../core/api/models';
import {
  groupByKind,
  kindInfo,
  locationChipLabel,
  locationTitle,
  normalizeLocationCode,
  placeOf,
} from './storage-location';

function location(overrides: Partial<StorageLocation>): StorageLocation {
  return {
    id: overrides.code ?? 'id',
    code: 'A1',
    name: 'Rayon A',
    kind: 'SHELF',
    sortOrder: 10,
    active: true,
    productCount: 0,
    updatedAt: '2026-10-27T00:00:00Z',
    ...overrides,
  };
}

describe('storage locations', () => {
  it('formats the counter chip with the detail when there is one', () => {
    expect(locationChipLabel('A3', 'case 12')).toBe('A3 · case 12');
    expect(locationChipLabel('FRIGO', '  ')).toBe('FRIGO');
    expect(locationChipLabel('COFFRE', null)).toBe('COFFRE');
  });

  it('describes the location in full for the tooltip', () => {
    expect(locationTitle('Rayon A · étagère 3', 'case 12', 'SHELF')).toBe(
      'Rayon A · étagère 3 — case 12 (Rayons et étagères)',
    );
    expect(locationTitle('Réfrigérateur', null, 'FRIDGE')).toBe('Réfrigérateur (Réfrigérateur)');
    expect(locationTitle('Vitrine')).toBe('Vitrine');
  });

  it('normalises codes like the server and refuses impossible ones', () => {
    expect(normalizeLocationCode(' a3 ')).toBe('A3');
    expect(normalizeLocationCode('réserve')).toBe('RESERVE');
    expect(normalizeLocationCode('tiroir   2')).toBe('TIROIR 2');
    expect(normalizeLocationCode('A3 · bas')).toBeNull();
    expect(normalizeLocationCode('ETAGERE DU FOND 12')).toBeNull();
    expect(normalizeLocationCode('')).toBeNull();
  });

  it('reads the place of an offline catalog entry, absent on entries cached before', () => {
    expect(
      placeOf({
        locationCode: 'T1',
        locationName: 'Tiroir 1',
        locationKind: 'DRAWER',
        locationDetail: 'bac 4',
      }),
    ).toEqual({ code: 'T1', name: 'Tiroir 1', kind: 'DRAWER', detail: 'bac 4' });
    expect(placeOf({ locationCode: 'X' })).toEqual({
      code: 'X',
      name: 'X',
      kind: null,
      detail: null,
    });
    expect(placeOf({})).toBeNull();
  });

  it('groups locations by kind in a fixed order and leaves empty kinds out', () => {
    const groups = groupByKind([
      location({ code: 'FRIGO', kind: 'FRIDGE', sortOrder: 70 }),
      location({ code: 'B1', sortOrder: 20 }),
      location({ code: 'A2', sortOrder: 10 }),
      location({ code: 'A1', sortOrder: 10 }),
      location({ code: 'COFFRE', kind: 'SAFE', sortOrder: 5 }),
    ]);

    expect(groups.map((g) => g.kind)).toEqual(['SHELF', 'FRIDGE', 'SAFE']);
    expect(groups[0].locations.map((l) => l.code)).toEqual(['A1', 'A2', 'B1']);
    expect(groups[1].icon).toBe('fridge');
  });

  it('falls back to the generic kind', () => {
    expect(kindInfo(null).kind).toBe('OTHER');
    expect(kindInfo('SAFE').icon).toBe('safe');
  });
});
