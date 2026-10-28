import { normalizePosology, posologyEndsOn, posologyIsEmpty, posologySummary } from './posology';

describe('posology', () => {
  it('summarises like the server, in the order of the day', () => {
    expect(
      posologySummary({
        morning: 1,
        noon: 0,
        evening: 1,
        bedtime: 0,
        durationDays: 7,
        withFood: true,
        instructions: "Avec un grand verre d'eau",
      }),
    ).toBe("1 matin · 1 soir · 7 jours · pendant les repas · Avec un grand verre d'eau");
    expect(
      posologySummary({
        morning: 0,
        noon: 0,
        evening: 0,
        bedtime: 1,
        durationDays: 1,
        withFood: false,
        instructions: null,
      }),
    ).toBe('1 coucher · 1 jour');
  });

  it('normalises what the stepper produced and drops an empty posology', () => {
    expect(
      normalizePosology({
        morning: 2.4,
        noon: -1,
        evening: 99,
        bedtime: 0,
        durationDays: 1000,
        withFood: true,
        instructions: '  ',
      }),
    ).toEqual({
      morning: 2,
      noon: 0,
      evening: 20,
      bedtime: 0,
      durationDays: 365,
      withFood: true,
      instructions: null,
    });
    expect(
      normalizePosology({
        morning: 0,
        noon: 0,
        evening: 0,
        bedtime: 0,
        durationDays: 0,
        withFood: true,
        instructions: '',
      }),
    ).toBeNull();
    expect(posologyIsEmpty(null)).toBe(true);
  });

  it('computes the last day of the treatment', () => {
    const start = new Date(2026, 9, 27);
    const end = posologyEndsOn(start, {
      morning: 1,
      noon: 0,
      evening: 0,
      bedtime: 0,
      durationDays: 7,
      withFood: false,
      instructions: null,
    });
    expect(end?.getDate()).toBe(2);
    expect(end?.getMonth()).toBe(10);
    expect(
      posologyEndsOn(start, {
        morning: 1,
        noon: 0,
        evening: 0,
        bedtime: 0,
        durationDays: 0,
        withFood: false,
        instructions: null,
      }),
    ).toBeNull();
  });
});
