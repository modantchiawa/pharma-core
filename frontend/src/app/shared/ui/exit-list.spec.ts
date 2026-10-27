import { describe, expect, it } from 'vitest';
import { Exiting, mergeExits } from './exit-list';

interface Line {
  id: string;
  quantity: number;
}
const key = (line: Line) => line.id;
const shown = (...lines: [Line, boolean][]): Exiting<Line>[] =>
  lines.map(([item, leaving]) => ({ item, leaving }));

describe('mergeExits', () => {
  it('adds new items at the end, in their order', () => {
    const { list, left } = mergeExits(
      [],
      [
        { id: 'a', quantity: 1 },
        { id: 'b', quantity: 2 },
      ],
      key,
    );
    expect(list.map((e) => [e.item.id, e.leaving])).toEqual([
      ['a', false],
      ['b', false],
    ]);
    expect(left).toEqual([]);
  });

  it('keeps a removed item in place as leaving and reports it once', () => {
    const before = shown([{ id: 'a', quantity: 1 }, false], [{ id: 'b', quantity: 1 }, false]);
    const first = mergeExits(before, [{ id: 'b', quantity: 1 }], key);
    expect(first.list.map((e) => [e.item.id, e.leaving])).toEqual([
      ['a', true],
      ['b', false],
    ]);
    expect(first.left).toEqual(['a']);
    const again = mergeExits(first.list, [{ id: 'b', quantity: 1 }], key);
    expect(again.left).toEqual([]);
  });

  it('takes the fresh value of the items still there', () => {
    const before = shown([{ id: 'a', quantity: 1 }, false]);
    const { list } = mergeExits(before, [{ id: 'a', quantity: 3 }], key);
    expect(list[0]).toEqual({ item: { id: 'a', quantity: 3 }, leaving: false });
  });

  it('brings back an item added again while it was leaving', () => {
    const before = shown([{ id: 'a', quantity: 1 }, true]);
    const { list, left } = mergeExits(before, [{ id: 'a', quantity: 1 }], key);
    expect(list).toEqual([{ item: { id: 'a', quantity: 1 }, leaving: false }]);
    expect(left).toEqual([]);
  });

  it('marks everything as leaving when the list is cleared', () => {
    const before = shown([{ id: 'a', quantity: 1 }, false], [{ id: 'b', quantity: 1 }, false]);
    const { list, left } = mergeExits(before, [], key);
    expect(list.every((e) => e.leaving)).toBe(true);
    expect(left).toEqual(['a', 'b']);
  });
});
