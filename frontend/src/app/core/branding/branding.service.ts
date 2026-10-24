import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Branding, BrandingView } from '../api/models';
import { APP_ENVIRONMENT } from '../config';
import { CurrencyService } from '../ui/currency.service';
import { isHexColor, readableTextOn } from '../util/color';

const CACHE_KEY = 'pharmacore.branding';

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
  private readonly logo = signal<string | null>(null);

  /** Effective branding, including unsaved changes being previewed in the settings screen. */
  readonly branding = computed<Branding>(() => ({ ...this.state(), ...(this.preview() ?? {}) }));
  readonly logoUrl = this.logo.asReadonly();
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
      this.setLogo(
        view.hasLogo
          ? `${this.env.apiUrl}/public/pharmacies/${encodeURIComponent(slug)}/logo`
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

  async uploadLogo(file: File): Promise<void> {
    const form = new FormData();
    form.append('file', file);
    const view = await firstValueFrom(
      this.http.put<BrandingView>(`${this.env.apiUrl}/v1/pharmacy/branding/logo`, form),
    );
    this.apply(view);
  }

  previewChanges(changes: Partial<Branding> | null): void {
    this.preview.set(changes);
  }

  private apply(view: BrandingView): void {
    this.currency.set(view.currency);
    this.state.set(view.branding);
    localStorage.setItem(CACHE_KEY, JSON.stringify(view.branding));
    if (view.hasLogo) {
      void this.fetchLogo();
    } else {
      this.setLogo(null);
    }
  }

  /** The logo endpoint is authenticated: fetch it as a blob and expose an object URL. */
  private async fetchLogo(): Promise<void> {
    try {
      const blob = await firstValueFrom(
        this.http.get(`${this.env.apiUrl}/v1/pharmacy/branding/logo`, { responseType: 'blob' }),
      );
      this.setLogo(URL.createObjectURL(blob));
    } catch {
      this.setLogo(null);
    }
  }

  private setLogo(url: string | null): void {
    const previous = this.logo();
    if (previous?.startsWith('blob:')) {
      URL.revokeObjectURL(previous);
    }
    this.logo.set(url);
  }
}

function safe(color: string | undefined, fallback: string): string {
  return isHexColor(color) ? color : fallback;
}

function readCache(): Branding | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    return raw ? (JSON.parse(raw) as Branding) : null;
  } catch {
    return null;
  }
}
