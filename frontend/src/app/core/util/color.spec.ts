import { contrastRatio, isHexColor, readableTextOn } from './color';

describe('color helpers', () => {
  it('validates hex colors', () => {
    expect(isHexColor('#0F766E')).toBe(true);
    expect(isHexColor('red')).toBe(false);
    expect(isHexColor('#FFF')).toBe(false);
  });

  it('computes WCAG contrast', () => {
    expect(contrastRatio('#FFFFFF', '#000000')).toBeCloseTo(21, 0);
    expect(contrastRatio('#777777', '#777777')).toBeCloseTo(1, 5);
  });

  it('picks a readable text color for any brand color', () => {
    expect(readableTextOn('#0F766E')).toBe('#FFFFFF');
    expect(readableTextOn('#FDE047')).toBe('#111827');
  });
});
