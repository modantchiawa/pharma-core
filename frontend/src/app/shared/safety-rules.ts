import { SafetyAlert, SafetyRule, SafetySeverity } from '../core/api/models';

/** What the rules need to know of a product of the basket. */
export interface BasketItem {
  productId: string;
  designation: string;
  dci: string | null;
  atcCode?: string | null;
}

const ATC_PREFIX = 'ATC:';
const MIN_COMPONENT_LENGTH = 3;
const SEVERITY_ORDER: Record<SafetySeverity, number> = {
  CONTRAINDICATED: 0,
  ADVISED_AGAINST: 1,
  PRECAUTION: 2,
};

/**
 * Pure port of the server's {@code SafetyRules} for what a terminal can evaluate alone:
 * interactions between the products of the basket and the same substance hidden in two products.
 * Same normalisation, same titles, same keys, so an override decided here is recognised by the
 * server when the sale is recorded. Allergies, patient situations and dispensing history need
 * the customer's file and stay on the server.
 */
export function normalizeSubstance(value: string | null | undefined): string {
  if (!value) {
    return '';
  }
  return value
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toUpperCase()
    .replace(/’/g, "'")
    .replace(/[^A-Z0-9:+/,()' -]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export function substanceComponents(dci: string | null | undefined): string[] {
  const normalized = normalizeSubstance(dci);
  if (!normalized) {
    return [];
  }
  const parts: string[] = [];
  for (const part of normalized.split(/[+/,]/)) {
    const cleaned = part
      .replace(/\(.*?\)/g, '')
      .replace(/\s+/g, ' ')
      .trim();
    if (cleaned.length >= MIN_COMPONENT_LENGTH && !parts.includes(cleaned)) {
      parts.push(cleaned);
    }
  }
  return parts;
}

export function matchesComponent(token: string, component: string): boolean {
  return component === token || component.startsWith(token + ' ');
}

export function tokenMatches(
  token: string,
  components: string[],
  atcCode: string | null | undefined,
): boolean {
  if (!token) {
    return false;
  }
  if (token.startsWith(ATC_PREFIX)) {
    const wanted = token.slice(ATC_PREFIX.length).trim().toUpperCase();
    return !!atcCode && wanted.length > 0 && normalizeSubstance(atcCode).startsWith(wanted);
  }
  return components.some((component) => matchesComponent(token, component));
}

export function severityRank(severity: SafetySeverity): number {
  return SEVERITY_ORDER[severity];
}

export function sortAlerts(alerts: SafetyAlert[]): SafetyAlert[] {
  return [...alerts].sort(
    (a, b) => severityRank(a.severity) - severityRank(b.severity) || a.title.localeCompare(b.title),
  );
}

function sortedIds(ids: Iterable<string>): string[] {
  return [...new Set(ids)].sort();
}

/** Interactions and class duplicates of the basket, from the cached referential. */
export function evaluateBasket(items: BasketItem[], rules: SafetyRule[]): SafetyAlert[] {
  const prepared = items.map((item) => ({ item, components: substanceComponents(item.dci) }));
  const alerts: SafetyAlert[] = [];
  for (const rule of rules) {
    if (rule.kind !== 'INTERACTION') {
      continue;
    }
    const first = prepared.filter((p) =>
      tokenMatches(rule.substance, p.components, p.item.atcCode),
    );
    if (first.length === 0) {
      continue;
    }
    const classDuplicate = rule.substance === rule.counterpart;
    const second = classDuplicate
      ? first
      : prepared.filter((p) => tokenMatches(rule.counterpart, p.components, p.item.atcCode));
    const involved = new Set<string>();
    for (const a of first) {
      for (const b of second) {
        if (a.item.productId !== b.item.productId) {
          involved.add(a.item.productId);
          involved.add(b.item.productId);
        }
      }
    }
    if (involved.size === 0) {
      continue;
    }
    const ids = sortedIds(involved);
    const names = items
      .filter((item) => involved.has(item.productId))
      .map((item) => item.designation)
      .join(' + ');
    alerts.push({
      kind: 'INTERACTION',
      severity: rule.severity,
      title: (classDuplicate ? 'Même classe : ' : 'Interaction : ') + names,
      detail: rule.advice,
      productIds: ids,
      key: `INTERACTION:${rule.substance}|${rule.counterpart}:${ids.join(',')}`,
    });
  }
  const byComponent = new Map<string, Set<string>>();
  const names = new Map<string, string>();
  for (const { item, components } of prepared) {
    names.set(item.productId, item.designation);
    for (const component of components) {
      const ids = byComponent.get(component) ?? new Set<string>();
      ids.add(item.productId);
      byComponent.set(component, ids);
    }
  }
  for (const [component, ids] of byComponent) {
    if (ids.size < 2) {
      continue;
    }
    const sorted = sortedIds(ids);
    const products = [...ids].map((id) => names.get(id)).join(', ');
    alerts.push({
      kind: 'DUPLICATE_SUBSTANCE',
      severity: 'ADVISED_AGAINST',
      title: `Doublon : ${component} dans ${ids.size} produits`,
      detail: `Risque de surdosage, la même substance est dans : ${products}.`,
      productIds: sorted,
      key: `DUPLICATE_SUBSTANCE:${component}:${sorted.join(',')}`,
    });
  }
  return sortAlerts(alerts);
}

/** Every leading word sequence of a component, the tokens the rule index is queried with. */
export function componentPrefixes(component: string): string[] {
  const prefixes: string[] = [];
  let current = '';
  for (const word of component.split(' ')) {
    current = current ? `${current} ${word}` : word;
    prefixes.push(current);
  }
  return prefixes;
}
