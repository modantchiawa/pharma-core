import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SwUpdate } from '@angular/service-worker';
import { filter } from 'rxjs';
import { BUILD_INFO, BuildInfo, isNewerBuild } from './build-info';

/** Checks for a new version this often, and each time the page comes back to the front. */
const CHECK_EVERY_MS = 10 * 60_000;
/** "Plus tard" puts the banner away for this long, never for good. */
const SNOOZE_MS = 60 * 60_000;

/**
 * Keeps every station on the published version. A new build is spotted two ways: the service
 * worker has downloaded it (VERSION_READY), or `version.json` on the server names another build
 * (also when the service worker is off or stuck). The station then shows a banner; updating
 * activates the new version, empties the browser's application caches and reloads. Sales waiting
 * for synchronization, the basket and the settings live in IndexedDB and local storage: they are
 * never touched.
 */
@Injectable({ providedIn: 'root' })
export class AppUpdateService {
  private readonly sw = inject(SwUpdate);
  private readonly destroyRef = inject(DestroyRef);

  readonly current: BuildInfo = BUILD_INFO;
  /** A newer build is waiting: the banner shows. */
  readonly available = signal(false);
  /** The newer build, when `version.json` described it. */
  readonly latest = signal<BuildInfo | null>(null);
  readonly snoozedUntil = signal(0);
  readonly checking = signal(false);
  readonly updating = signal(false);
  private started = false;

  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    if (this.sw.isEnabled) {
      this.sw.versionUpdates
        .pipe(
          filter((e) => e.type === 'VERSION_READY'),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe(() => this.available.set(true));
      // the cached version can no longer run (files removed from the server): reload clean
      this.sw.unrecoverable
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => void this.forceReload());
    }
    const timer = setInterval(() => void this.check(), CHECK_EVERY_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        void this.check();
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    this.destroyRef.onDestroy(() => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    });
    void this.check();
  }

  /** Asks the server whether a newer build is published; true when one is. */
  async check(): Promise<boolean> {
    this.checking.set(true);
    try {
      if (this.sw.isEnabled) {
        await this.sw.checkForUpdate().catch(() => false);
      }
      const response = await fetch(`version.json?t=${Date.now()}`, { cache: 'no-store' });
      if (response.ok) {
        const remote = (await response.json()) as BuildInfo;
        if (isNewerBuild(remote)) {
          this.latest.set(remote);
          this.available.set(true);
        }
      }
    } catch {
      // offline or no version.json (development): the service worker check is enough
    } finally {
      this.checking.set(false);
    }
    return this.available();
  }

  /** Hides the banner for an hour. */
  snooze(): void {
    this.snoozedUntil.set(Date.now() + SNOOZE_MS);
    setTimeout(() => this.snoozedUntil.set(0), SNOOZE_MS);
  }

  /** Switches to the new version: activate it, empty the caches, reload. */
  async update(): Promise<void> {
    this.updating.set(true);
    if (this.sw.isEnabled) {
      await this.sw.activateUpdate().catch(() => false);
    }
    await this.forceReload();
  }

  /**
   * Last resort when a station is stuck on an old or broken version: unregisters the service
   * worker, deletes the application caches (Cache Storage) and reloads from the server.
   * IndexedDB (pending sales, basket, catalogue) and local storage stay as they are.
   */
  async forceReload(): Promise<void> {
    this.updating.set(true);
    try {
      const registrations = (await navigator.serviceWorker?.getRegistrations?.()) ?? [];
      await Promise.all(registrations.map((r) => r.unregister()));
      if ('caches' in window) {
        const keys = await caches.keys();
        await Promise.all(keys.map((key) => caches.delete(key)));
      }
    } finally {
      location.reload();
    }
  }
}
