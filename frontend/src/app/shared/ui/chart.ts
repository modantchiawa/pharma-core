/**
 * Column grown from the baseline with a 4px rounded data-end and a square foot, as an SVG path.
 * A zero or negative height draws nothing.
 */
export function columnPath(x: number, width: number, height: number, baseline: number): string {
  if (height <= 0) {
    return '';
  }
  const r = Math.min(4, width / 2, height);
  const top = baseline - height;
  return (
    `M${x},${baseline}V${top + r}Q${x},${top} ${x + r},${top}` +
    `H${x + width - r}Q${x + width},${top} ${x + width},${top + r}V${baseline}Z`
  );
}

/**
 * Axis ceiling for four gridlines landing on round amounts: the smallest 1/2/2.5/5 × 10^n step
 * whose fourfold covers the value.
 */
export function niceCeiling(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(value / 4));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s * 4 >= value)!;
  return step * 4;
}
