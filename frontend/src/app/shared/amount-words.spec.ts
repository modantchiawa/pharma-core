import { amountInWords, integerInWords } from './amount-words';

describe('integerInWords', () => {
  it('spells the French irregulars', () => {
    expect(integerInWords(0)).toBe('zéro');
    expect(integerInWords(21)).toBe('vingt et un');
    expect(integerInWords(71)).toBe('soixante et onze');
    expect(integerInWords(80)).toBe('quatre-vingts');
    expect(integerInWords(81)).toBe('quatre-vingt-un');
    expect(integerInWords(99)).toBe('quatre-vingt-dix-neuf');
    expect(integerInWords(200)).toBe('deux cents');
    expect(integerInWords(201)).toBe('deux cent un');
    expect(integerInWords(1000)).toBe('mille');
    expect(integerInWords(1250)).toBe('mille deux cent cinquante');
    expect(integerInWords(2_000_000)).toBe('deux millions');
    expect(integerInWords(1_234_567)).toBe(
      'un million deux cent trente-quatre mille cinq cent soixante-sept',
    );
  });

  it('refuses what is not a whole number', () => {
    expect(() => integerInWords(-1)).toThrow(RangeError);
    expect(() => integerInWords(1.5)).toThrow(RangeError);
  });
});

describe('amountInWords', () => {
  it('spells XOF without decimals and EUR with centimes', () => {
    expect(amountInWords(1250, 'XOF')).toBe('mille deux cent cinquante francs CFA');
    expect(amountInWords(1, 'XOF')).toBe('un franc CFA');
    expect(amountInWords(1250, 'EUR')).toBe('douze euros et cinquante centimes');
    expect(amountInWords(100, 'EUR')).toBe('un euro');
    expect(amountInWords(-500, 'XOF')).toBe('moins cinq cents francs CFA');
  });
});
