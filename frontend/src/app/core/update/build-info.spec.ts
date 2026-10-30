import { describe, expect, it } from 'vitest';
import { isNewerBuild, versionLabel } from './build-info';

const built = { version: '0.2.0', commit: 'a1b2c3d', builtAt: '2026-10-30T10:00:00.000Z' };

describe('build info', () => {
  it('labels the version with its commit when known', () => {
    expect(versionLabel(built)).toBe('0.2.0 · a1b2c3d');
    expect(versionLabel({ version: '0.2.0', commit: '', builtAt: null })).toBe('0.2.0');
  });

  it('sees a newer build by its commit or its build instant', () => {
    expect(isNewerBuild(built, built)).toBe(false);
    expect(isNewerBuild({ ...built, commit: 'f00ba47' }, built)).toBe(true);
    expect(isNewerBuild({ ...built, builtAt: '2026-10-31T08:00:00.000Z' }, built)).toBe(true);
    expect(isNewerBuild({ ...built, commit: '' }, built)).toBe(false);
  });

  it('never prompts a development page or on an unreadable answer', () => {
    expect(isNewerBuild(built, { version: '0.1.0', commit: '', builtAt: null })).toBe(false);
    expect(isNewerBuild(null, built)).toBe(false);
  });
});
