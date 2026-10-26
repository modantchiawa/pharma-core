import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Branding, BrandingView } from '../api/models';
import { APP_ENVIRONMENT } from '../config';
import { CurrencyService } from '../ui/currency.service';
import { isHexColor, readableTextOn } from '../util/color';

const CACHE_KEY = 'pharmacore.branding';
const LOGO_CACHE_KEY = 'pharmacore.logo';
/** Data URLs above this size are not cached (localStorage is shared with the offline POS). */
const LOGO_CACHE_MAX_CHARS = 900_000;

export const DEFAULT_BRANDING: Branding = {
  displayName: 'PharmaCore',
  addressLine1: null,
  addressLine2: null,
  postalCode: null,
  city: null,
  countryCode: 'FR',
  phone: null,
  email: null,
  legalIdentifier: null,
  finessNumber: null,
  ordonnancierPrefix: null,
  primaryColor: '#0F766E',
  secondaryColor: '#134E4A',
  accentColor: '#F59E0B',
  receiptHeader: null,
  receiptFooter: null,
};

/**
 * White-label theming driven by signals: the pharmacy's colors are published as CSS custom
 * properties (consumed by Tailwind's theme), with an automatically contrasted text color.
 * The last branding is cached so the offline POS and its receipts keep the pharmacy identity.
 */
@Injectable({ providedIn: 'root' })
export class BrandingService {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly document = inject(DOCUMENT);
  private readonly currency = inject(CurrencyService);
  private readonly state = signal<Branding>(readCache() ?? DEFAULT_BRANDING);
  private readonly preview = signal<Partial<Branding> | null>(null);
  private readonly logo = signal<string | null>(readLogoCache());
  /** Bumped on every logo change: a download started before the change must not undo it. */
  private logoGeneration = 0;

  /** Effective branding, including unsaved changes being previewed in the settings screen. */
  readonly branding = computed<Branding>(() => ({ ...this.state(), ...(this.preview() ?? {}) }));
  /** Data URL of the cached logo (offline POS, tickets) or public URL before login. */
  readonly logoUrl = this.logo.asReadonly();
  readonly hasLogo = computed(() => this.logo() !== null);
  readonly onPrimary = computed(() =>
    readableTextOn(safe(this.branding().primaryColor, DEFAULT_BRANDING.primaryColor)),
  );
  readonly fullAddress = computed(() => {
    const b = this.branding();
    return [b.addressLine1, b.addressLine2, [b.postalCode, b.city].filter(Boolean).join(' ')]
      .filter((part) => !!part)
      .join(', ');
  });

  constructor() {
    effect(() => {
      const b = this.branding();
      const root = this.document.documentElement.style;
      root.setProperty('--brand-primary', safe(b.primaryColor, DEFAULT_BRANDING.primaryColor));
      root.setProperty(
        '--brand-secondary',
        safe(b.secondaryColor, DEFAULT_BRANDING.secondaryColor),
      );
      root.setProperty('--brand-accent', safe(b.accentColor, DEFAULT_BRANDING.accentColor));
      root.setProperty('--brand-on-primary', this.onPrimary());
      this.document.title = b.displayName;
    });
    // The pharmacy's logo as the browser tab icon (back to the default icon without logo)
    const favicon = this.document.querySelector<HTMLLinkElement>('link[rel="icon"]');
    const defaultIcon = favicon?.getAttribute('href') ?? null;
    effect(() => {
      const logo = this.logo();
      if (favicon && defaultIcon) {
        favicon.removeAttribute('type');
        favicon.href = logo ?? defaultIcon;
      }
    });
  }

  async load(): Promise<void> {
    try {
      const view = await firstValueFrom(
        this.http.get<BrandingView>(`${this.env.apiUrl}/v1/pharmacy/branding`),
      );
      this.apply(view);
    } catch {
      // Offline: keep the cached identity.
    }
  }

  /** Pre-login identity resolved from the pharmacy slug (e.g. https://{slug}.pharmacore.io). */
  async loadPublic(slug: string): Promise<void> {
    try {
      const view = await firstValueFrom(
        this.http.get<BrandingView>(
          `${this.env.apiUrl}/public/pharmacies/${encodeURIComponent(slug)}/branding`,
        ),
      );
      this.state.set(view.branding);
      this.logoGeneration++;
      // The version busts the one-hour public cache after a logo change
      this.setLogo(
        view.hasLogo
          ? `${this.env.apiUrl}/public/pharmacies/${encodeURIComponent(slug)}/logo?v=${view.version}`
          : null,
      );
    } catch {
      // Unknown slug: default identity.
    }
  }

  async save(branding: Branding): Promise<void> {
    const view = await firstValueFrom(
      this.http.put<BrandingView>(`${this.env.apiUrl}/v1/pharmacy/branding`, branding),
    );
    this.preview.set(null);
    this.apply(view);
  }

  /** Accepts the prepared logo (see shared/ui/logo-file.ts) as a file or a redrawn image. */
  async uploadLogo(file: Blob): Promise<void> {
    const form = new FormData();
    form.append('file', file, file instanceof File ? file.name : 'logo');
    const view = await firstValueFrom(
      this.http.put<BrandingView>(`${this.env.apiUrl}/v1/pharmacy/branding/logo`, form),
    );
    // The bytes just sent are the new logo: no need to download them back
    this.apply(view, await toDataUrl(file));
  }

  async removeLogo(): Promise<void> {
    const view = await firstValueFrom(
      this.http.delete<BrandingView>(`${this.env.apiUrl}/v1/pharmacy/branding/logo`),
    );
    this.apply(view);
  }

  previewChanges(changes: Partial<Branding> | null): void {
    this.preview.set(changes);
  }

  private apply(view: BrandingView, logo?: string): void {
    this.currency.set(view.currency);
    this.state.set(view.branding);
    localStorage.setItem(CACHE_KEY, JSON.stringify(view.branding));
    const generation = ++this.logoGeneration;
    if (!view.hasLogo) {
      this.setLogo(null);
    } else if (logo) {
      this.setLogo(logo);
    } else {
      void this.fetchLogo(generation);
    }
  }

  /**
   * The logo endpoint is authenticated: fetch it as a blob and keep it as a data URL, cached so
   * the logo shows at once on the next start and on tickets printed offline.
   */
  private async fetchLogo(generation: number): Promise<void> {
    try {
      const blob = await firstValueFrom(
        this.http.get(`${this.env.apiUrl}/v1/pharmacy/branding/logo`, { responseType: 'blob' }),
      );
      const url = await toDataUrl(blob);
      if (generation === this.logoGeneration) {
        this.setLogo(url);
      }
    } catch {
      // Offline: keep the cached logo
    }
  }

  private setLogo(url: string | null): void {
    this.logo.set(url);
    try {
      if (url?.startsWith('data:') && url.length <= LOGO_CACHE_MAX_CHARS) {
        localStorage.setItem(LOGO_CACHE_KEY, url);
      } else if (url === null) {
        localStorage.removeItem(LOGO_CACHE_KEY);
      }
    } catch {
      // Storage full or disabled: the logo stays in memory for this session
    }
  }
}

function safe(color: string | undefined, fallback: string): string {
  return isHexColor(color) ? color : fallback;
}

function toDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function readLogoCache(): string | null {
  try {
    return localStorage.getItem(LOGO_CACHE_KEY);
  } catch {
    return null;
  }
}

function readCache(): Branding | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    return raw ? (JSON.parse(raw) as Branding) : null;
  } catch {
    return null;
  }
}
