import { StorageLocation, StorageLocationKind } from '../core/api/models';
import { IconName } from './ui/icons';

export interface LocationKindInfo {
  kind: StorageLocationKind;
  label: string;
  icon: IconName;
  hint: string;
}

/** Kinds in the order the Emplacements screen groups them. */
export const LOCATION_KINDS: readonly LocationKindInfo[] = [
  { kind: 'SHELF', label: 'Rayons et étagères', icon: 'shelf', hint: 'Rayon, étagère, linéaire' },
  {
    kind: 'DRAWER',
    label: 'Tiroirs',
    icon: 'drawer',
    hint: 'Injectables, ampoules, petits formats',
  },
  { kind: 'FRIDGE', label: 'Réfrigérateur', icon: 'fridge', hint: 'Chaîne du froid 2-8 °C' },
  { kind: 'SAFE', label: 'Coffre', icon: 'safe', hint: 'Stupéfiants, sous clé' },
  { kind: 'COUNTER', label: 'Comptoir', icon: 'store', hint: 'Au comptoir ou derrière' },
  { kind: 'RESERVE', label: 'Réserve', icon: 'warehouse', hint: 'Surstock, arrière-boutique' },
  { kind: 'OTHER', label: 'Autres', icon: 'mapPin', hint: 'Vitrine, présentoir…' },
];

const BY_KIND = new Map(LOCATION_KINDS.map((k) => [k.kind, k]));

export function kindInfo(kind: StorageLocationKind | null | undefined): LocationKindInfo {
  return BY_KIND.get(kind ?? 'OTHER') ?? (BY_KIND.get('OTHER') as LocationKindInfo);
}

/** Where a product is stored, whatever the source (stock list, offline catalog, cart line). */
export interface ProductPlace {
  code: string;
  name: string;
  kind: StorageLocationKind | null;
  detail: string | null;
}

/** Place of an offline catalog entry; null when the product is not put away. */
export function placeOf(item: {
  locationCode?: string | null;
  locationName?: string | null;
  locationKind?: StorageLocationKind | null;
  locationDetail?: string | null;
}): ProductPlace | null {
  return item.locationCode
    ? {
        code: item.locationCode,
        name: item.locationName ?? item.locationCode,
        kind: item.locationKind ?? null,
        detail: item.locationDetail ?? null,
      }
    : null;
}

/** Compact text of the counter chip: "A3 · case 12", or the code alone. */
export function locationChipLabel(code: string, detail?: string | null): string {
  const extra = detail?.trim();
  return extra ? `${code} · ${extra}` : code;
}

/** Full description for a tooltip or a screen reader: "Rayon A · étagère 3 — case 12 (Rayons et étagères)". */
export function locationTitle(
  name: string,
  detail?: string | null,
  kind?: StorageLocationKind | null,
): string {
  const extra = detail?.trim();
  const where = extra ? `${name} — ${extra}` : name;
  return kind ? `${where} (${kindInfo(kind).label})` : where;
}

export const LOCATION_CODE_MAX = 16;
const VALID_CODE = new RegExp(`^[A-Z0-9][A-Z0-9 ._/-]{0,${LOCATION_CODE_MAX - 1}}$`);

/**
 * Canonical code, as the server stores it (upper case, no accents, single spaces), or null when
 * the input cannot be a code: lets the form show the code that will be saved and refuse a bad one
 * before sending it.
 */
export function normalizeLocationCode(raw: string): string | null {
  const code = raw
    .trim()
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toUpperCase()
    .replace(/\s+/g, ' ');
  return VALID_CODE.test(code) ? code : null;
}

export interface LocationGroup extends LocationKindInfo {
  locations: StorageLocation[];
}

/** Locations grouped by kind (fixed kind order, then sort order and code); empty kinds are left out. */
export function groupByKind(locations: readonly StorageLocation[]): LocationGroup[] {
  return LOCATION_KINDS.map((info) => ({
    ...info,
    locations: locations
      .filter((l) => l.kind === info.kind)
      .sort((a, b) => a.sortOrder - b.sortOrder || a.code.localeCompare(b.code)),
  })).filter((group) => group.locations.length > 0);
}
