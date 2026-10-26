import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { APP_ENVIRONMENT } from '../config';

export interface NodeInfo {
  mode: 'HUB' | 'CLOUD';
  nodeId: string;
  paired: boolean;
  pharmacySlug: string | null;
  cloudUrl: string | null;
  version: string;
}

const CACHE_KEY = 'pharmacore.nodeInfo';

/**
 * Which node this PWA talks to. On a pharmacy LAN it is the local hub, which serves the app
 * itself; inter-pharmacy features then go straight to the cloud with the same token.
 */
@Injectable({ providedIn: 'root' })
export class NodeInfoService {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly state = signal<NodeInfo | null>(readCache());

  readonly info = this.state.asReadonly();
  readonly isHub = computed(() => this.state()?.mode === 'HUB');
  readonly isPaired = computed(() => this.state()?.paired ?? true);
  /** Base URL of the local API (hub or cloud). */
  readonly apiUrl = computed(() => this.env.apiUrl);
  /** Base URL of the cloud API for network features: the cloud itself, or this node when it is the cloud. */
  readonly networkApiUrl = computed(() => {
    const info = this.state();
    return info?.mode === 'HUB' && info.cloudUrl
      ? `${info.cloudUrl.replace(/\/$/, '')}/api`
      : this.env.apiUrl;
  });
  readonly networkWsUrl = computed(() => {
    const info = this.state();
    if (info?.mode === 'HUB' && info.cloudUrl) {
      return info.cloudUrl.replace(/^http/, 'ws').replace(/\/$/, '') + '/ws';
    }
    return this.env.wsUrl;
  });

  async load(): Promise<NodeInfo | null> {
    try {
      const info = await firstValueFrom(
        this.http.get<NodeInfo>(`${this.env.apiUrl}/public/hub/info`),
      );
      this.state.set(info);
      localStorage.setItem(CACHE_KEY, JSON.stringify(info));
      return info;
    } catch {
      return this.state();
    }
  }
}

function readCache(): NodeInfo | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    return raw ? (JSON.parse(raw) as NodeInfo) : null;
  } catch {
    return null;
  }
}
