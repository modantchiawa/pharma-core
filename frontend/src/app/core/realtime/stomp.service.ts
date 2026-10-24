import { Injectable, inject, signal } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { Observable } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { NodeInfoService } from '../api/node-info.service';

/**
 * Single STOMP connection shared by the app. The bearer token is read before every
 * (re)connection, and subscriptions are restored automatically after a reconnect.
 */
@Injectable({ providedIn: 'root' })
export class StompService {
  private readonly node = inject(NodeInfoService);
  private readonly auth = inject(AuthService);
  private readonly registry = new Map<string, Set<(message: IMessage) => void>>();
  private readonly active = new Map<string, StompSubscription>();
  private client: Client | null = null;

  readonly connected = signal(false);

  watch<T>(destination: string): Observable<T> {
    return new Observable<T>((subscriber) => {
      const handler = (message: IMessage) => subscriber.next(JSON.parse(message.body) as T);
      const handlers = this.registry.get(destination) ?? new Set();
      handlers.add(handler);
      this.registry.set(destination, handlers);
      this.ensureClient();
      this.subscribeIfConnected(destination);
      return () => {
        handlers.delete(handler);
        if (handlers.size === 0) {
          this.registry.delete(destination);
          this.active.get(destination)?.unsubscribe();
          this.active.delete(destination);
        }
      };
    });
  }

  publish(destination: string, body: unknown): void {
    if (!this.client?.connected) {
      throw new Error('Messagerie déconnectée');
    }
    this.client.publish({ destination, body: JSON.stringify(body) });
  }

  disconnect(): void {
    void this.client?.deactivate();
    this.client = null;
    this.active.clear();
    this.connected.set(false);
  }

  private ensureClient(): void {
    if (this.client) {
      return;
    }
    const client = new Client({
      brokerURL: absoluteWsUrl(this.node.networkWsUrl()),
      reconnectDelay: 5_000,
      heartbeatIncoming: 20_000,
      heartbeatOutgoing: 20_000,
      beforeConnect: async () => {
        const token = this.auth.accessToken() ?? (await this.auth.refresh());
        client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },
      onConnect: () => {
        this.connected.set(true);
        this.active.clear();
        for (const destination of this.registry.keys()) {
          this.subscribeIfConnected(destination);
        }
      },
      onWebSocketClose: () => this.connected.set(false),
      onStompError: () => this.connected.set(false),
    });
    this.client = client;
    client.activate();
  }

  private subscribeIfConnected(destination: string): void {
    if (!this.client?.connected || this.active.has(destination)) {
      return;
    }
    const subscription = this.client.subscribe(destination, (message) => {
      this.registry.get(destination)?.forEach((handler) => handler(message));
    });
    this.active.set(destination, subscription);
  }
}

function absoluteWsUrl(url: string): string {
  if (url.startsWith('ws')) {
    return url;
  }
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${location.host}${url}`;
}
