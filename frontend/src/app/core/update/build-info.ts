import { environment } from '../../../environments/environment';

/** What was built: replaced at build time by `scripts/build.mjs` (`--define PHARMACORE_BUILD=…`). */
declare const PHARMACORE_BUILD: BuildInfo | undefined;

export interface BuildInfo {
  /** Version of the application (package.json, or the demo label). */
  version: string;
  /** Short commit the build comes from; empty when unknown (development). */
  commit: string;
  /** ISO instant of the build; null in development. */
  builtAt: string | null;
}

export const BUILD_INFO: BuildInfo =
  typeof PHARMACORE_BUILD !== 'undefined'
    ? PHARMACORE_BUILD
    : { version: environment.appVersion, commit: '', builtAt: null };

/** "0.1.0 · a1b2c3d": what the screens, the heartbeat and the support show (≤ 40 characters). */
export function versionLabel(info: BuildInfo = BUILD_INFO): string {
  return (info.commit ? `${info.version} · ${info.commit}` : info.version).slice(0, 40);
}

/**
 * Is the build published on the server another one than this page's? Compared on the build
 * instant and the commit; a page without build stamp (development) never sees an update.
 */
export function isNewerBuild(
  remote: Partial<BuildInfo> | null,
  local: BuildInfo = BUILD_INFO,
): boolean {
  if (!remote || !local.builtAt) {
    return false;
  }
  if (remote.commit && local.commit && remote.commit !== local.commit) {
    return true;
  }
  return !!remote.builtAt && remote.builtAt !== local.builtAt;
}
