import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject, signal } from '@angular/core';

export type Density = 'COMPACT' | 'COMFORTABLE' | 'LARGE';

export interface DisplayPreferences {
  density: Density;
  highContrast: boolean;
  fontScale: 1 | 1.15 | 1.3;
  darkMode: boolean;
  /** Very quiet cues at the counter (item scanned, sale recorded). */
  sounds: boolean;
}

const KEY = 'pharmacore.display';
const DEFAULTS: DisplayPreferences = {
  density: 'COMFORTABLE',
  highContrast: false,
  fontScale: 1,
  darkMode: false,
  sounds: false,
};

/**
 * Per-terminal look and feel: density for small screens, large text and high contrast for
 * sunlit counters, dark mode for night duty. Applied as attributes on <html> and consumed by
 * the stylesheet; independent from the pharmacy's brand colors.
 */
@Injectable({ providedIn: 'root' })
export class DisplayPreferencesService {
  private readonly document = inject(DOCUMENT);
  private readonly state = signal<DisplayPreferences>(read());

  readonly preferences = this.state.asReadonly();

  constructor() {
    effect(() => this.apply(this.state()));
  }

  /** Applied at once too, so that a theme switch can be captured by a view transition. */
  update(changes: Partial<DisplayPreferences>): void {
    this.state.update((current) => ({ ...current, ...changes }));
    this.apply(this.state());
    localStorage.setItem(KEY, JSON.stringify(this.state()));
  }

  private apply(p: DisplayPreferences): void {
    const root = this.document.documentElement;
    root.dataset['density'] = p.density.toLowerCase();
    root.dataset['contrast'] = p.highContrast ? 'high' : 'normal';
    root.dataset['theme'] = p.darkMode ? 'dark' : 'light';
    root.style.setProperty('--font-scale', String(p.fontScale));
  }
}

function read(): DisplayPreferences {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? { ...DEFAULTS, ...(JSON.parse(raw) as Partial<DisplayPreferences>) } : DEFAULTS;
  } catch {
    return DEFAULTS;
  }
}
