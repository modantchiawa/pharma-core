import { Injectable, inject, signal } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { Observable } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { NodeInfoService } from '../api/node-info.service';
import { APP_ENVIRONMENT } from '../config';

/**
 * Where a subscription lives: the network broker (cloud: chat, transfers) or this node's own
 * broker (hub or cloud: events of the pharmacy's operations, such as internal control alerts).
 * On the cloud both are the same connection.
 */
export type StompTarget = 'network' | 'local';

interface Connection {
  client: Client;
  registry: Map<string, Set<(message: IMessage) => void>>;
  active: Map<string, StompSubscription>;
  connected: (value: boolean) => void;
}

/**
 * STOMP connections shared by the app, one per broker URL. The bearer token is read before
 * every (re)connection, and subscriptions are restored automatically after a reconnect.
 */
@Injectable({ providedIn: 'root' })
export class StompService {
  private readonly node = inject(NodeInfoService);
  private readonly auth = inject(AuthService);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly connections = new Map<string, Connection>();

  /** State of the network connection (chat, transfers). */
  readonly connected = signal(false);
  /** State of this node's connection (live alerts). */
  readonly localConnected = signal(false);

  watch<T>(destination: string, target: StompTarget = 'network'): Observable<T> {
    return new Observable<T>((subscriber) => {
      const connection = this.connection(target);
      const handler = (message: IMessage) => subscriber.next(JSON.parse(message.body) as T);
      const handlers = connection.registry.get(destination) ?? new Set();
      handlers.add(handler);
      connection.registry.set(destination, handlers);
      this.subscribeIfConnected(connection, destination);
      return () => {
        handlers.delete(handler);
        if (handlers.size === 0) {
          connection.registry.delete(destination);
          connection.active.get(destination)?.unsubscribe();
          connection.active.delete(destination);
        }
      };
    });
  }

  publish(destination: string, body: unknown): void {
    const client = this.connections.get(this.url('network'))?.client;
    if (!client?.connected) {
      throw new Error('Messagerie déconnectée');
    }
    client.publish({ destination, body: JSON.stringify(body) });
  }

  disconnect(): void {
    for (const connection of this.connections.values()) {
      void connection.client.deactivate();
      connection.active.clear();
    }
    this.connections.clear();
    this.connected.set(false);
    this.localConnected.set(false);
  }

  private url(target: StompTarget): string {
    return absoluteWsUrl(target === 'network' ? this.node.networkWsUrl() : this.env.wsUrl);
  }

  private connection(target: StompTarget): Connection {
    const url = this.url(target);
    const existing = this.connections.get(url);
    if (existing) {
      return existing;
    }
    const network = url === this.url('network');
    const local = url === this.url('local');
    const connection: Connection = {
      client: new Client({
        brokerURL: url,
        reconnectDelay: 5_000,
        heartbeatIncoming: 20_000,
        heartbeatOutgoing: 20_000,
      }),
      registry: new Map(),
      active: new Map(),
      connected: (value) => {
        if (network) {
          this.connected.set(value);
        }
        if (local) {
          this.localConnected.set(value);
        }
      },
    };
    const client = connection.client;
    client.beforeConnect = async () => {
      const token = this.auth.accessToken() ?? (await this.auth.refresh());
      client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    };
    client.onConnect = () => {
      connection.connected(true);
      connection.active.clear();
      for (const destination of connection.registry.keys()) {
        this.subscribeIfConnected(connection, destination);
      }
    };
    client.onWebSocketClose = () => connection.connected(false);
    client.onStompError = () => connection.connected(false);
    this.connections.set(url, connection);
    client.activate();
    return connection;
  }

  private subscribeIfConnected(connection: Connection, destination: string): void {
    if (!connection.client.connected || connection.active.has(destination)) {
      return;
    }
    const subscription = connection.client.subscribe(destination, (message) => {
      connection.registry.get(destination)?.forEach((handler) => handler(message));
    });
    connection.active.set(destination, subscription);
  }
}

function absoluteWsUrl(url: string): string {
  if (url.startsWith('ws')) {
    return url;
  }
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${location.host}${url}`;
}
