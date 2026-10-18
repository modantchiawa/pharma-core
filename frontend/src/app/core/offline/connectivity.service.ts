import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';

/**
 * Browser connectivity combined with actual server reachability: {@code navigator.onLine} only
 * says a network interface is up, the sync loop reports whether the API really answers.
 */
@Injectable({ providedIn: 'root' })
export class ConnectivityService {
  private readonly networkUp = signal(navigator.onLine);
  private readonly serverUp = signal(true);

  readonly online = computed(() => this.networkUp() && this.serverUp());
  readonly networkAvailable = this.networkUp.asReadonly();

  constructor() {
    const onOnline = () => this.networkUp.set(true);
    const onOffline = () => this.networkUp.set(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    });
  }

  reportServerReachable(reachable: boolean): void {
    this.serverUp.set(reachable);
  }
}
