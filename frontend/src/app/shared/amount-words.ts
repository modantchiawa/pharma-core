const UNITS = [
  'zéro',
  'un',
  'deux',
  'trois',
  'quatre',
  'cinq',
  'six',
  'sept',
  'huit',
  'neuf',
  'dix',
  'onze',
  'douze',
  'treize',
  'quatorze',
  'quinze',
  'seize',
];
const TENS = ['', 'dix', 'vingt', 'trente', 'quarante', 'cinquante', 'soixante'];

/** 0..99 in French, with the "et un" / "soixante-onze" / "quatre-vingt-dix" rules. */
function belowHundred(n: number): string {
  if (n < 17) {
    return UNITS[n];
  }
  if (n < 20) {
    return `dix-${UNITS[n - 10]}`;
  }
  if (n < 70) {
    const tens = Math.floor(n / 10);
    const unit = n % 10;
    if (unit === 0) {
      return TENS[tens];
    }
    return unit === 1 ? `${TENS[tens]} et un` : `${TENS[tens]}-${UNITS[unit]}`;
  }
  if (n < 80) {
    const rest = n - 60;
    return rest === 11 ? 'soixante et onze' : `soixante-${belowHundred(rest)}`;
  }
  const rest = n - 80;
  if (rest === 0) {
    return 'quatre-vingts';
  }
  return `quatre-vingt-${belowHundred(rest)}`;
}

/** 0..999 in French: "cent" is plural only when it ends the number ("deux cents", "deux cent un"). */
function belowThousand(n: number): string {
  const hundreds = Math.floor(n / 100);
  const rest = n % 100;
  if (hundreds === 0) {
    return belowHundred(rest);
  }
  const head = hundreds === 1 ? 'cent' : `${UNITS[hundreds]} cent${rest === 0 ? 's' : ''}`;
  return rest === 0 ? head : `${head} ${belowHundred(rest)}`;
}

/** A non-negative integer in French words ("mille deux cent cinquante", "un million"). */
export function integerInWords(value: number): string {
  if (!Number.isInteger(value) || value < 0) {
    throw new RangeError('integerInWords expects a non-negative integer');
  }
  if (value === 0) {
    return 'zéro';
  }
  const scales: [number, string, string][] = [
    [1_000_000_000, 'milliard', 'milliards'],
    [1_000_000, 'million', 'millions'],
    [1_000, 'mille', 'mille'],
  ];
  const parts: string[] = [];
  let rest = value;
  for (const [size, singular, plural] of scales) {
    const count = Math.floor(rest / size);
    rest %= size;
    if (count === 0) {
      continue;
    }
    if (size === 1_000) {
      parts.push(count === 1 ? 'mille' : `${belowThousand(count)} mille`);
    } else {
      parts.push(`${belowThousand(count)} ${count > 1 ? plural : singular}`);
    }
  }
  if (rest > 0) {
    parts.push(belowThousand(rest));
  }
  return parts.join(' ');
}

const CURRENCY_NAMES: Record<
  string,
  { major: [string, string]; minor: [string, string]; exponent: number }
> = {
  XOF: { major: ['franc CFA', 'francs CFA'], minor: ['', ''], exponent: 0 },
  XAF: { major: ['franc CFA', 'francs CFA'], minor: ['', ''], exponent: 0 },
  GNF: { major: ['franc guinéen', 'francs guinéens'], minor: ['', ''], exponent: 0 },
  EUR: { major: ['euro', 'euros'], minor: ['centime', 'centimes'], exponent: 2 },
  USD: { major: ['dollar', 'dollars'], minor: ['cent', 'cents'], exponent: 2 },
  MAD: { major: ['dirham', 'dirhams'], minor: ['centime', 'centimes'], exponent: 2 },
};

/**
 * An amount in minor units spelled out for the invoice footer: 1250 XOF →
 * "mille deux cent cinquante francs CFA", 1250 EUR cents → "douze euros et cinquante centimes".
 */
export function amountInWords(minor: number, currency = 'XOF'): string {
  const names = CURRENCY_NAMES[currency] ?? {
    major: [currency, currency],
    minor: ['', ''],
    exponent: 2,
  };
  const abs = Math.abs(Math.round(minor));
  const divisor = 10 ** names.exponent;
  const major = Math.floor(abs / divisor);
  const cents = abs % divisor;
  let text = `${integerInWords(major)} ${major > 1 ? names.major[1] : names.major[0]}`;
  if (cents > 0 && names.minor[0]) {
    text += ` et ${integerInWords(cents)} ${cents > 1 ? names.minor[1] : names.minor[0]}`;
  }
  return (minor < 0 ? 'moins ' : '') + text;
}
