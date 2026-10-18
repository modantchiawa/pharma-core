import { Injectable, computed, inject, signal } from '@angular/core';
import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { APP_ENVIRONMENT } from '../config';

export type Realm = 'pharmacy' | 'platform';

const REALM_KEY = 'pharmacore.realm';

/**
 * OpenID Connect (authorization code + PKCE) against the pharmacy realm or the editor's
 * platform realm. Tokens live in sessionStorage only; offline sales never need a token, the
 * journal is synchronized once the session can be refreshed.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly realmState = signal<Realm>(
    (sessionStorage.getItem(REALM_KEY) as Realm) ?? 'pharmacy',
  );
  private readonly userState = signal<User | null>(null);
  private manager = this.createManager(this.realmState());

  readonly realm = this.realmState.asReadonly();
  readonly user = this.userState.asReadonly();
  readonly isAuthenticated = computed(() => {
    const user = this.userState();
    return !!user && !user.expired;
  });
  readonly accessToken = computed(() => this.userState()?.access_token ?? null);
  readonly claims = computed(() => decodeJwtPayload(this.accessToken()));
  readonly tenantId = computed(() => (this.claims()?.['tenant_id'] as string | undefined) ?? null);
  readonly isPlatformOperator = computed(() => {
    const roles = this.claims()?.['platform_roles'];
    return Array.isArray(roles) && roles.includes('SUPER_ADMIN');
  });

  async init(): Promise<void> {
    this.userState.set(await this.manager.getUser());
  }

  async login(realm: Realm, returnUrl = '/'): Promise<void> {
    sessionStorage.setItem(REALM_KEY, realm);
    this.realmState.set(realm);
    this.manager = this.createManager(realm);
    await this.manager.signinRedirect({ state: returnUrl });
  }

  async completeLogin(): Promise<string> {
    const user = await this.manager.signinRedirectCallback();
    this.userState.set(user);
    return typeof user.state === 'string' ? user.state : '/';
  }

  /** Called by the HTTP layer on 401: tries a silent refresh before giving up. */
  async refresh(): Promise<string | null> {
    try {
      const user = await this.manager.signinSilent();
      this.userState.set(user);
      return user?.access_token ?? null;
    } catch {
      return null;
    }
  }

  async logout(): Promise<void> {
    await this.manager.signoutRedirect();
  }

  private createManager(realm: Realm): UserManager {
    const config = this.env.oidc[realm];
    const manager = new UserManager({
      authority: config.authority,
      client_id: config.clientId,
      redirect_uri: `${location.origin}/auth/callback`,
      post_logout_redirect_uri: location.origin,
      response_type: 'code',
      scope: this.env.oidc.scope,
      automaticSilentRenew: true,
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    });
    manager.events.addUserLoaded((user) => this.userState.set(user));
    manager.events.addUserUnloaded(() => this.userState.set(null));
    return manager;
  }
}

export function decodeJwtPayload(token: string | null): Record<string, unknown> | null {
  if (!token) {
    return null;
  }
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const json = decodeURIComponent(
      Array.from(atob(payload), (c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(
        '',
      ),
    );
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}
