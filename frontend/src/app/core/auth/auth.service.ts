import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { firstValueFrom } from 'rxjs';
import { Me } from '../api/models';
import { APP_ENVIRONMENT } from '../config';
import { PermissionService } from './permission.service';

export type Realm = 'pharmacy' | 'platform';

const REALM_KEY = 'pharmacore.realm';
const TERMINAL_TOKEN_KEY = 'pharmacore.terminalToken';
const REFRESH_KEY = 'pharmacore.refreshToken';
const ACCESS_KEY = 'pharmacore.accessToken';
const PHARMACY_KEY = 'pharmacore.pharmacy';
const PLATFORM_TOKEN_KEY = 'pharmacore.platformToken';

interface TokenResponse {
  accessToken: string;
  accessExpiresAt: string;
  refreshToken: string;
  terminalToken: string | null;
  mustChangePassword: boolean;
  profile: Me;
}

/**
 * Two ways in:
 * <ul>
 *   <li>Pharmacy staff: local accounts verified by the node (hub or cloud), no Internet needed.
 *       A password login binds the terminal for 30 days; afterwards colleagues switch with a PIN.</li>
 *   <li>Editor operators: OpenID Connect (PKCE) on the platform realm, for the supervision console.</li>
 * </ul>
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly http = inject(HttpClient);
  private readonly permissions = inject(PermissionService);
  private readonly realmState = signal<Realm>(
    (sessionStorage.getItem(REALM_KEY) as Realm) ?? 'pharmacy',
  );
  private readonly accessState = signal<string | null>(sessionStorage.getItem(ACCESS_KEY));
  private readonly mustChange = signal(false);
  private readonly platformUser = signal<User | null>(null);
  /** Platform token obtained by password in demonstration mode (no identity provider). */
  private readonly platformToken = signal<string | null>(
    sessionStorage.getItem(PLATFORM_TOKEN_KEY),
  );
  private platformManager: UserManager | null = null;
  private refreshing: Promise<string | null> | null = null;

  readonly realm = this.realmState.asReadonly();
  readonly mustChangePassword = this.mustChange.asReadonly();
  readonly accessToken = computed(() =>
    this.realmState() === 'platform'
      ? (this.platformToken() ?? this.platformUser()?.access_token ?? null)
      : this.accessState(),
  );
  readonly platformDisplayName = computed(
    () =>
      (this.claims()?.['name'] as string | undefined) ??
      (this.platformUser()?.profile?.name as string | undefined) ??
      null,
  );
  readonly claims = computed(() => decodeJwtPayload(this.accessToken()));
  readonly tenantId = computed(() => (this.claims()?.['tenant_id'] as string | undefined) ?? null);
  readonly isPlatformOperator = computed(() => {
    const roles = this.claims()?.['platform_roles'];
    return Array.isArray(roles) && roles.includes('SUPER_ADMIN');
  });
  readonly isAuthenticated = computed(() => {
    const exp = this.claims()?.['exp'];
    return typeof exp === 'number' && exp * 1000 > Date.now();
  });
  /** Users who can switch in with a PIN on this terminal (last successful logins). */
  readonly terminalBound = computed(() => !!localStorage.getItem(TERMINAL_TOKEN_KEY));
  readonly user = this.platformUser.asReadonly();

  async init(): Promise<void> {
    if (this.realmState() === 'platform') {
      if (this.platformToken()) {
        return;
      }
      this.platformUser.set(await this.manager().getUser());
    } else if (!this.isAuthenticated() && localStorage.getItem(REFRESH_KEY)) {
      await this.refresh();
    }
  }

  // ------------------------------------------------------------------ pharmacy staff

  async loginWithPassword(
    pharmacy: string | null,
    email: string,
    password: string,
    terminalId: string,
  ): Promise<Me> {
    const response = await firstValueFrom(
      this.http.post<TokenResponse>(`${this.env.apiUrl}/auth/login`, {
        pharmacy,
        email,
        password,
        terminalId,
      }),
    );
    if (pharmacy) {
      localStorage.setItem(PHARMACY_KEY, pharmacy);
    }
    if (response.terminalToken) {
      localStorage.setItem(TERMINAL_TOKEN_KEY, response.terminalToken);
    }
    this.rememberKnownUser(email, response.profile.displayName);
    return this.accept(response);
  }

  async loginWithPin(email: string, pin: string, terminalId: string): Promise<Me> {
    const terminalToken = localStorage.getItem(TERMINAL_TOKEN_KEY);
    if (!terminalToken) {
      throw new Error('Ce poste doit être ouvert avec un mot de passe');
    }
    const response = await firstValueFrom(
      this.http.post<TokenResponse>(`${this.env.apiUrl}/auth/pin`, {
        terminalToken,
        email,
        pin,
        terminalId,
      }),
    );
    return this.accept(response);
  }

  /** Called by the HTTP layer on 401 and at startup; concurrent callers share one refresh. */
  refresh(): Promise<string | null> {
    if (this.realmState() === 'platform') {
      return this.refreshPlatform();
    }
    this.refreshing ??= this.doRefresh().finally(() => (this.refreshing = null));
    return this.refreshing;
  }

  async logout(): Promise<void> {
    if (this.realmState() === 'platform') {
      if (this.platformToken()) {
        sessionStorage.removeItem(PLATFORM_TOKEN_KEY);
        sessionStorage.setItem(REALM_KEY, 'pharmacy');
        this.platformToken.set(null);
        this.realmState.set('pharmacy');
        return;
      }
      await this.manager().signoutRedirect();
      return;
    }
    const refreshToken = localStorage.getItem(REFRESH_KEY);
    localStorage.removeItem(REFRESH_KEY);
    sessionStorage.removeItem(ACCESS_KEY);
    this.accessState.set(null);
    this.permissions.clear();
    if (refreshToken) {
      try {
        await firstValueFrom(this.http.post(`${this.env.apiUrl}/auth/logout`, { refreshToken }));
      } catch {
        // Offline: the server session expires by itself.
      }
    }
  }

  /** Staff who signed in on this terminal, offered for PIN switching. */
  knownUsers(): { email: string; name: string }[] {
    try {
      return JSON.parse(localStorage.getItem('pharmacore.knownUsers') ?? '[]') as {
        email: string;
        name: string;
      }[];
    } catch {
      return [];
    }
  }

  lastPharmacy(): string | null {
    return localStorage.getItem(PHARMACY_KEY);
  }

  passwordChanged(): void {
    this.mustChange.set(false);
  }

  // ------------------------------------------------------------------ platform console (OIDC)

  async loginPlatform(returnUrl = '/platform'): Promise<void> {
    sessionStorage.setItem(REALM_KEY, 'platform');
    this.realmState.set('platform');
    await this.manager().signinRedirect({ state: returnUrl });
  }

  /** Demonstration mode: the supervision console opens with a shared password. */
  async loginPlatformWithPassword(password: string): Promise<void> {
    const response = await firstValueFrom(
      this.http.post<{ accessToken: string }>(`${this.env.apiUrl}/auth/platform-login`, {
        password,
      }),
    );
    sessionStorage.setItem(REALM_KEY, 'platform');
    sessionStorage.setItem(PLATFORM_TOKEN_KEY, response.accessToken);
    this.realmState.set('platform');
    this.platformToken.set(response.accessToken);
  }

  async completePlatformLogin(): Promise<string> {
    const user = await this.manager().signinRedirectCallback();
    this.platformUser.set(user);
    return typeof user.state === 'string' ? user.state : '/platform';
  }

  // ------------------------------------------------------------------ internals

  private accept(response: TokenResponse): Me {
    sessionStorage.setItem(REALM_KEY, 'pharmacy');
    this.realmState.set('pharmacy');
    sessionStorage.setItem(ACCESS_KEY, response.accessToken);
    localStorage.setItem(REFRESH_KEY, response.refreshToken);
    this.accessState.set(response.accessToken);
    this.mustChange.set(response.mustChangePassword);
    this.permissions.setProfile(response.profile);
    return response.profile;
  }

  private async doRefresh(): Promise<string | null> {
    const refreshToken = localStorage.getItem(REFRESH_KEY);
    if (!refreshToken) {
      return null;
    }
    try {
      const response = await firstValueFrom(
        this.http.post<TokenResponse>(`${this.env.apiUrl}/auth/refresh`, { refreshToken }),
      );
      this.accept(response);
      return response.accessToken;
    } catch (error) {
      if ((error as { status?: number }).status === 422) {
        localStorage.removeItem(REFRESH_KEY);
      }
      return null;
    }
  }

  private async refreshPlatform(): Promise<string | null> {
    if (this.platformToken()) {
      return this.isAuthenticated() ? this.platformToken() : null;
    }
    try {
      const user = await this.manager().signinSilent();
      this.platformUser.set(user);
      return user?.access_token ?? null;
    } catch {
      return null;
    }
  }

  private rememberKnownUser(email: string, name: string): void {
    const users = this.knownUsers().filter((u) => u.email !== email);
    users.unshift({ email, name });
    localStorage.setItem('pharmacore.knownUsers', JSON.stringify(users.slice(0, 8)));
  }

  private manager(): UserManager {
    if (!this.platformManager) {
      const config = this.env.oidc.platform;
      this.platformManager = new UserManager({
        authority: config.authority,
        client_id: config.clientId,
        redirect_uri: `${location.origin}/auth/callback`,
        post_logout_redirect_uri: location.origin,
        response_type: 'code',
        scope: this.env.oidc.scope,
        automaticSilentRenew: true,
        userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      });
      this.platformManager.events.addUserLoaded((user) => this.platformUser.set(user));
      this.platformManager.events.addUserUnloaded(() => this.platformUser.set(null));
    }
    return this.platformManager;
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
