/** How the patient takes a product, as typed at the counter; mirrors the server's {@code Posology}. */
export interface Posology {
  morning: number;
  noon: number;
  evening: number;
  bedtime: number;
  durationDays: number;
  withFood: boolean;
  instructions: string | null;
}

export const MAX_UNITS_PER_TAKE = 20;
export const MAX_DURATION_DAYS = 365;
export const MAX_INSTRUCTIONS = 120;

/** The moments of the day, in order, with the pictogram and the word printed under it. */
export const POSOLOGY_MOMENTS = [
  { key: 'morning', label: 'Matin', icon: 'sunrise' },
  { key: 'noon', label: 'Midi', icon: 'sun' },
  { key: 'evening', label: 'Soir', icon: 'sunset' },
  { key: 'bedtime', label: 'Coucher', icon: 'moon' },
] as const;

export type PosologyMoment = (typeof POSOLOGY_MOMENTS)[number]['key'];

export function emptyPosology(): Posology {
  return {
    morning: 0,
    noon: 0,
    evening: 0,
    bedtime: 0,
    durationDays: 0,
    withFood: false,
    instructions: null,
  };
}

export function posologyIsEmpty(posology: Posology | null | undefined): boolean {
  return (
    !posology ||
    (dailyUnits(posology) === 0 && posology.durationDays === 0 && !posology.instructions?.trim())
  );
}

export function dailyUnits(posology: Posology): number {
  return posology.morning + posology.noon + posology.evening + posology.bedtime;
}

/** Bounds every field like the server does and drops an all-empty posology. */
export function normalizePosology(posology: Posology | null | undefined): Posology | null {
  if (!posology) {
    return null;
  }
  const clamp = (value: number, max: number) =>
    Math.min(Math.max(Math.round(Number(value) || 0), 0), max);
  const cleaned: Posology = {
    morning: clamp(posology.morning, MAX_UNITS_PER_TAKE),
    noon: clamp(posology.noon, MAX_UNITS_PER_TAKE),
    evening: clamp(posology.evening, MAX_UNITS_PER_TAKE),
    bedtime: clamp(posology.bedtime, MAX_UNITS_PER_TAKE),
    durationDays: clamp(posology.durationDays, MAX_DURATION_DAYS),
    withFood: !!posology.withFood,
    instructions: posology.instructions?.trim().slice(0, MAX_INSTRUCTIONS) || null,
  };
  return posologyIsEmpty(cleaned) ? null : cleaned;
}

/** "1 matin · 1 soir · 7 jours · pendant les repas", exactly as the server prints it. */
export function posologySummary(posology: Posology | null | undefined): string {
  if (!posology) {
    return '';
  }
  const parts: string[] = [];
  if (posology.morning > 0) {
    parts.push(`${posology.morning} matin`);
  }
  if (posology.noon > 0) {
    parts.push(`${posology.noon} midi`);
  }
  if (posology.evening > 0) {
    parts.push(`${posology.evening} soir`);
  }
  if (posology.bedtime > 0) {
    parts.push(`${posology.bedtime} coucher`);
  }
  if (posology.durationDays > 0) {
    parts.push(`${posology.durationDays} ${posology.durationDays > 1 ? 'jours' : 'jour'}`);
  }
  if (posology.withFood && dailyUnits(posology) > 0) {
    parts.push('pendant les repas');
  }
  if (posology.instructions?.trim()) {
    parts.push(posology.instructions.trim());
  }
  return parts.join(' · ');
}

/** Last day of the treatment started on {@code start}; null without a duration. */
export function posologyEndsOn(start: Date, posology: Posology): Date | null {
  if (posology.durationDays <= 0) {
    return null;
  }
  const end = new Date(start);
  end.setDate(end.getDate() + posology.durationDays - 1);
  return end;
}
