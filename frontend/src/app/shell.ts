import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  ViewContainerRef,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { PermissionService } from './core/auth/permission.service';
import { BrandingService } from './core/branding/branding.service';
import { ConnectivityService } from './core/offline/connectivity.service';
import { SaleOutboxService } from './core/offline/sale-outbox.service';
import { IdleService } from './core/session/idle.service';
import { LockScreen } from './core/session/lock-screen';
import { LicenseBanner } from './core/subscription/license-banner';
import { LicenseStateService } from './core/subscription/license-state.service';
import { Density, DisplayPreferencesService } from './core/ui/display-preferences.service';
import { Icon } from './shared/ui/icon';
import { IconName } from './shared/ui/icons';
import { DURATION, EASING, prefersReducedMotion } from './shared/ui/motion';

interface NavItem {
  path: string;
  label: string;
  icon: IconName;
  permissions: string[];
  hint?: string;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

const PHARMACY_NAV: NavGroup[] = [
  {
    label: 'Comptoir',
    items: [
      {
        path: '/pos',
        label: 'Vente',
        icon: 'cart',
        permissions: ['SALE_CREATE'],
        hint: 'F2 pour scanner',
      },
      {
        path: '/cash',
        label: 'Caisse',
        icon: 'cash',
        permissions: ['CASH_SESSION_MANAGE', 'REPORT_VIEW'],
      },
      {
        path: '/cash/payments',
        label: 'Paiements',
        icon: 'mobileMoney',
        permissions: ['PAYMENT_RECONCILE'],
      },
      { path: '/customers', label: 'Clients', icon: 'customers', permissions: ['CUSTOMER_MANAGE'] },
      {
        path: '/prescriptions',
        label: 'Ordonnances',
        icon: 'prescription',
        permissions: ['PRESCRIPTION_DISPENSE', 'PRESCRIPTION_REGISTER_VIEW'],
      },
      {
        path: '/insurance',
        label: 'Tiers payant',
        icon: 'insurance',
        permissions: ['INSURANCE_VIEW', 'INSURANCE_MANAGE'],
      },
    ],
  },
  {
    label: 'Stock et achats',
    items: [
      { path: '/inventory', label: 'Stocks', icon: 'stock', permissions: ['STOCK_VIEW'] },
      {
        path: '/inventory/counts',
        label: 'Inventaire',
        icon: 'counts',
        permissions: ['STOCK_COUNT', 'STOCK_COUNT_VALIDATE'],
      },
      {
        path: '/inventory/locations',
        label: 'Emplacements',
        icon: 'locations',
        permissions: ['STOCK_VIEW', 'STOCK_LOCATION_MANAGE'],
      },
      {
        path: '/procurement',
        label: 'Commandes',
        icon: 'procurement',
        permissions: ['PROCUREMENT_VIEW'],
      },
    ],
  },
  {
    label: 'Gestion',
    items: [
      {
        path: '/dashboard',
        label: 'Pilotage',
        icon: 'dashboard',
        permissions: ['DASHBOARD_VIEW', 'REPORT_VIEW'],
      },
      {
        path: '/accounting',
        label: 'Comptabilité',
        icon: 'accounting',
        permissions: ['ACCOUNTING_VIEW'],
      },
      {
        path: '/control',
        label: 'Contrôle interne',
        icon: 'activity',
        permissions: ['CONTROL_VIEW'],
      },
      { path: '/control/alerts', label: 'Alertes', icon: 'bell', permissions: ['CONTROL_VIEW'] },
    ],
  },
  {
    label: 'Réseau',
    items: [
      {
        path: '/network',
        label: 'Pharmacies partenaires',
        icon: 'network',
        permissions: [
          'NETWORK_STOCK_QUERY',
          'NETWORK_TRANSFER_REQUEST',
          'NETWORK_TRANSFER_RESPOND',
        ],
      },
      { path: '/guards', label: 'Gardes', icon: 'guards', permissions: ['GUARD_VIEW'] },
      { path: '/chat', label: 'Messagerie', icon: 'chat', permissions: ['NETWORK_CHAT'] },
    ],
  },
  {
    label: 'Administration',
    items: [
      {
        path: '/admin/branding',
        label: 'Identité visuelle',
        icon: 'branding',
        permissions: ['CONFIG_BRANDING_MANAGE'],
      },
      {
        path: '/admin/roles',
        label: 'Utilisateurs et droits',
        icon: 'roles',
        permissions: ['ROLE_MANAGE'],
      },
      {
        path: '/admin/import',
        label: 'Import de données',
        icon: 'import',
        permissions: ['DATA_IMPORT'],
      },
      {
        path: '/admin/printers',
        label: 'Imprimantes',
        icon: 'printer',
        permissions: ['PRINTER_MANAGE'],
      },
      { path: '/admin/hub', label: 'Serveur local', icon: 'server', permissions: ['HUB_ADMIN'] },
      {
        path: '/control/settings',
        label: 'Règles de contrôle',
        icon: 'settings',
        permissions: ['CONTROL_SETTINGS'],
      },
    ],
  },
];

const PLATFORM_NAV: NavGroup[] = [
  {
    label: 'Éditeur',
    items: [
      { path: '/platform', label: 'Supervision', icon: 'building', permissions: [] },
      { path: '/platform/catalog-import', label: 'Référentiel', icon: 'catalog', permissions: [] },
      { path: '/platform/subscriptions', label: 'Abonnements', icon: 'license', permissions: [] },
    ],
  },
];

const COLLAPSED_KEY = 'pharmacore.sidebarCollapsed';

interface PaletteEntry {
  label: string;
  group: string;
  icon: IconName;
  run: () => void;
}

/** Application frame: grouped side navigation, top bar with status, command palette. */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    FormsModule,
    Icon,
    LicenseBanner,
    LockScreen,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:keydown)': 'onKeydown($event)',
    '(document:click)': 'onDocumentClick($event)',
  },
  template: `
    <div
      class="flex h-screen overflow-hidden bg-[var(--app-bg)] text-ink"
      [attr.inert]="idle.locked() ? '' : null"
    >
      <!-- Mobile backdrop -->
      @if (mobileOpen()) {
        <div
          class="fixed inset-0 z-30 bg-slate-950/40 backdrop-blur-[2px] lg:hidden print:hidden"
          (click)="mobileOpen.set(false)"
        ></div>
      }

      <!-- Sidebar -->
      <aside
        class="sidebar fixed inset-y-0 left-0 z-40 flex flex-col border-r border-line bg-surface transition-[width,transform] duration-200 lg:static lg:translate-x-0 print:hidden"
        [class.w-[17rem]]="!collapsed() || mobileOpen()"
        [class.w-[4.5rem]]="collapsed() && !mobileOpen()"
        [class.-translate-x-full]="!mobileOpen()"
        [class.translate-x-0]="mobileOpen()"
        aria-label="Navigation principale"
      >
        <div class="flex h-16 shrink-0 items-center gap-3 px-4">
          <a
            [routerLink]="canEditBranding() ? '/admin/branding' : null"
            fragment="logo"
            class="brand-mark group"
            [class.pointer-events-none]="!canEditBranding()"
            [attr.title]="
              canEditBranding()
                ? branding.hasLogo()
                  ? 'Changer le logo'
                  : 'Ajouter votre logo'
                : null
            "
            [attr.aria-label]="canEditBranding() ? 'Logo de la pharmacie : modifier' : null"
          >
            @if (branding.logoUrl(); as logo) {
              <img [src]="logo" alt="" class="h-full w-full object-contain" />
            } @else {
              <span class="text-sm font-bold">{{ initials(branding.branding().displayName) }}</span>
            }
            @if (canEditBranding()) {
              <span class="brand-mark-edit"><app-icon name="pencil" [size]="12" /></span>
            }
          </a>
          @if (expanded()) {
            <div class="min-w-0 leading-tight">
              <p class="truncate text-sm font-semibold">{{ branding.branding().displayName }}</p>
              <p class="truncate text-xs text-muted">PharmaCore</p>
            </div>
          }
        </div>

        <div class="px-3 pb-2">
          <button
            type="button"
            class="flex w-full items-center gap-2 rounded-lg border border-line bg-surface-2 px-3 py-2 text-sm text-subtle transition hover:border-[var(--border-strong)] hover:text-muted"
            [class.justify-center]="!expanded()"
            (click)="openPalette()"
            title="Rechercher (Ctrl K)"
          >
            <app-icon name="search" [size]="16" />
            @if (expanded()) {
              <span class="flex-1 text-left">Rechercher…</span>
              <span class="kbd">{{ isMac ? '⌘' : 'Ctrl' }} K</span>
            }
          </button>
        </div>

        <nav class="flex-1 overflow-y-auto px-3 pb-4">
          @for (group of nav(); track group.label) {
            <div class="mt-4 first:mt-2">
              @if (expanded()) {
                <p
                  class="px-3 pb-1.5 text-[0.7rem] font-semibold uppercase tracking-wider text-subtle"
                >
                  {{ group.label }}
                </p>
              } @else {
                <div class="mx-3 mb-2 h-px bg-[var(--border)]"></div>
              }
              @for (item of group.items; track item.path) {
                <a
                  [routerLink]="item.path"
                  routerLinkActive="nav-active"
                  [routerLinkActiveOptions]="{ exact: true }"
                  class="nav-link group"
                  [class.justify-center]="!expanded()"
                  [attr.title]="expanded() ? null : item.label"
                  (click)="mobileOpen.set(false)"
                >
                  <app-icon [name]="item.icon" [size]="18" />
                  @if (expanded()) {
                    <span class="truncate">{{ item.label }}</span>
                  }
                </a>
              }
            </div>
          }
        </nav>

        <div class="border-t border-line p-3">
          @if (!auth.isPlatformOperator()) {
            <div
              class="mb-2 flex items-center gap-2.5 rounded-lg px-3 py-2 text-xs"
              [class.bg-[var(--success-soft)]]="connectivity.online()"
              [class.text-success]="connectivity.online()"
              [class.bg-[var(--warning-soft)]]="!connectivity.online()"
              [class.text-warning]="!connectivity.online()"
              [class.justify-center]="!expanded()"
              role="status"
            >
              <app-icon [name]="connectivity.online() ? 'wifi' : 'wifiOff'" [size]="16" />
              @if (expanded()) {
                <span class="font-semibold">{{
                  connectivity.online() ? 'En ligne' : 'Hors ligne'
                }}</span>
                <span class="ml-auto text-[0.7rem] opacity-80">
                  {{
                    outbox.pendingCount() > 0
                      ? outbox.pendingCount() + ' en attente'
                      : 'synchronisé'
                  }}
                </span>
              }
            </div>
          }
          <button
            type="button"
            class="nav-link hidden w-full lg:flex"
            [class.justify-center]="!expanded()"
            (click)="toggleCollapsed()"
            [attr.title]="collapsed() ? 'Déplier le menu' : 'Replier le menu'"
          >
            <app-icon [name]="collapsed() ? 'panelOpen' : 'panelClose'" [size]="18" />
            @if (expanded()) {
              <span>Replier le menu</span>
            }
          </button>
        </div>
      </aside>

      <!-- Main column -->
      <div class="flex min-w-0 flex-1 flex-col">
        <!-- z-30: the blur makes the header its own stacking context; without a z-index the
             page content (cards, sticky table headers) painted over its menus -->
        <header
          class="relative z-30 flex h-16 shrink-0 items-center gap-3 border-b border-line bg-surface/80 px-4 backdrop-blur supports-[backdrop-filter]:bg-surface/70 lg:px-6 print:hidden"
        >
          <button
            type="button"
            class="btn btn-ghost btn-icon lg:hidden"
            (click)="mobileOpen.set(true)"
            aria-label="Ouvrir le menu"
          >
            <app-icon name="menu" />
          </button>
          <div class="page-heading min-w-0">
            @if (current(); as page) {
              <p class="text-xs font-medium text-subtle">{{ page.group }}</p>
              <h1 class="truncate text-base font-semibold leading-tight">{{ page.label }}</h1>
            }
          </div>

          <div class="ml-auto flex items-center gap-1.5">
            @if (!auth.isPlatformOperator()) {
              @if (outbox.rejectedCount() > 0) {
                <a
                  routerLink="/admin/hub"
                  class="badge badge-danger"
                  title="Ventes refusées par le serveur"
                >
                  <app-icon name="alert" [size]="13" /> {{ outbox.rejectedCount() }} rejet(s)
                </a>
              }
              <span
                class="badge hidden sm:inline-flex"
                [class.badge-success]="connectivity.online()"
                [class.badge-warning]="!connectivity.online()"
              >
                <span class="dot"></span>
                {{ connectivity.online() ? 'En ligne' : 'Hors ligne' }}
                @if (outbox.pendingCount() > 0) {
                  · {{ outbox.pendingCount() }} à synchroniser
                }
              </span>
            }

            @if (!auth.isPlatformOperator()) {
              <button
                type="button"
                class="btn btn-ghost btn-icon"
                (click)="idle.lockNow()"
                [attr.title]="'Verrouiller le poste (' + (isMac ? '⌘' : 'Ctrl') + ' L)'"
                aria-label="Verrouiller le poste"
                data-testid="lock-now"
              >
                <app-icon name="lock" />
              </button>
            }
            <ng-container #alertsBell />

            <button
              type="button"
              class="btn btn-ghost btn-icon"
              (click)="toggleTheme($event)"
              [attr.title]="
                display.preferences().darkMode ? 'Mode clair' : 'Mode sombre (garde de nuit)'
              "
            >
              <app-icon [name]="display.preferences().darkMode ? 'sun' : 'moon'" />
            </button>

            <div class="relative" #displayMenu>
              <button
                type="button"
                class="btn btn-ghost btn-icon"
                (click)="toggleMenu('display')"
                title="Affichage du poste"
                [attr.aria-expanded]="menu() === 'display'"
              >
                <app-icon name="settings" />
              </button>
              @if (menu() === 'display') {
                <div class="popover right-0 w-72 animate-in">
                  <p class="mb-3 text-sm font-semibold">Affichage de ce poste</p>
                  <p class="label">Densité</p>
                  <div class="segmented mb-3 w-full">
                    @for (d of densities; track d.value) {
                      <button
                        type="button"
                        class="flex-1"
                        [attr.aria-selected]="display.preferences().density === d.value"
                        (click)="setDensity(d.value)"
                      >
                        {{ d.label }}
                      </button>
                    }
                  </div>
                  <p class="label">Taille du texte</p>
                  <div class="segmented mb-3 w-full">
                    @for (s of scales; track s.value) {
                      <button
                        type="button"
                        class="flex-1"
                        [attr.aria-selected]="display.preferences().fontScale === s.value"
                        (click)="setScale(s.value)"
                      >
                        {{ s.label }}
                      </button>
                    }
                  </div>
                  <label class="flex items-center justify-between gap-3 py-1.5 text-sm">
                    <span>Contraste élevé <span class="text-subtle">(soleil)</span></span>
                    <input
                      type="checkbox"
                      class="toggle"
                      [checked]="display.preferences().highContrast"
                      (change)="toggle('highContrast', $event)"
                    />
                  </label>
                  <label class="flex items-center justify-between gap-3 py-1.5 text-sm">
                    <span>Mode sombre <span class="text-subtle">(garde)</span></span>
                    <input
                      type="checkbox"
                      class="toggle"
                      [checked]="display.preferences().darkMode"
                      (change)="toggle('darkMode', $event)"
                    />
                  </label>
                  <label class="flex items-center justify-between gap-3 py-1.5 text-sm">
                    <span>Sons discrets <span class="text-subtle">(caisse)</span></span>
                    <input
                      type="checkbox"
                      class="toggle"
                      [checked]="display.preferences().sounds"
                      (change)="toggle('sounds', $event)"
                    />
                  </label>
                </div>
              }
            </div>

            <div class="relative" #userMenu>
              <button
                type="button"
                class="flex items-center gap-2.5 rounded-lg py-1 pr-2 pl-1 transition hover:bg-surface-3"
                (click)="toggleMenu('user')"
                [attr.aria-expanded]="menu() === 'user'"
              >
                <span
                  class="flex h-8 w-8 items-center justify-center rounded-full bg-brand-soft text-xs font-bold text-brand"
                  >{{ initials(userName()) }}</span
                >
                <span class="hidden max-w-[10rem] truncate text-sm font-medium md:block">{{
                  userName()
                }}</span>
                <app-icon
                  name="chevronDown"
                  [size]="16"
                  class="hidden text-subtle md:inline-flex"
                />
              </button>
              @if (menu() === 'user') {
                <div class="popover right-0 w-64 animate-in p-1.5">
                  <div class="px-3 py-2">
                    <p class="truncate text-sm font-semibold">{{ userName() }}</p>
                    <p class="truncate text-xs text-muted">{{ roleLabel() }}</p>
                  </div>
                  <div class="divider my-1"></div>
                  @if (!auth.isPlatformOperator()) {
                    <a routerLink="/account/password" class="menu-item" (click)="menu.set(null)">
                      <app-icon name="key" [size]="16" /> Mot de passe et PIN
                    </a>
                    <button type="button" class="menu-item w-full" (click)="lock()">
                      <app-icon name="lock" [size]="16" /> Verrouiller le poste
                      <span class="kbd ml-auto whitespace-nowrap"
                        >{{ isMac ? '⌘' : 'Ctrl' }} L</span
                      >
                    </button>
                  }
                  @if (canSeeSubscription()) {
                    <a
                      routerLink="/account/subscription"
                      class="menu-item"
                      (click)="menu.set(null)"
                    >
                      <app-icon name="license" [size]="16" /> Mon abonnement
                    </a>
                  }
                  @if (canEditBranding()) {
                    <a
                      routerLink="/admin/branding"
                      fragment="logo"
                      class="menu-item"
                      (click)="menu.set(null)"
                    >
                      <app-icon name="image" [size]="16" /> Logo et couleurs
                    </a>
                  }
                  <button type="button" class="menu-item w-full" (click)="openPalette()">
                    <app-icon name="command" [size]="16" /> Recherche rapide
                    <span class="kbd ml-auto whitespace-nowrap">{{ isMac ? '⌘' : 'Ctrl' }} K</span>
                  </button>
                  <div class="divider my-1"></div>
                  <button
                    type="button"
                    class="menu-item w-full text-[var(--danger-strong)]"
                    (click)="logout()"
                  >
                    <app-icon name="logout" [size]="16" /> Déconnexion
                  </button>
                </div>
              }
            </div>
          </div>
        </header>

        @if (auth.mustChangePassword()) {
          <div class="alert alert-warning m-4 mb-0 lg:mx-6 print:hidden">
            <app-icon name="lock" [size]="18" />
            <span
              >Votre mot de passe est temporaire.
              <a routerLink="/account/password" class="font-semibold underline"
                >Changez-le maintenant</a
              >.</span
            >
          </div>
        }

        @if (license.notice(); as notice) {
          <app-license-banner [state]="notice" [link]="canSeeSubscription()" />
        }

        <main class="min-h-0 flex-1 overflow-auto"><router-outlet /></main>
      </div>

      <!-- Command palette -->
      @if (paletteOpen()) {
        <div
          class="palette-backdrop fixed inset-0 z-50 flex items-start justify-center bg-slate-950/40 p-4 pt-[12vh] backdrop-blur-sm print:hidden"
          [class.palette-leave]="paletteClosing()"
          (click)="closePalette()"
        >
          <div
            class="palette-panel w-full max-w-xl overflow-hidden rounded-2xl border border-line bg-surface shadow-[var(--shadow-lg)]"
            (click)="$event.stopPropagation()"
            role="dialog"
            aria-label="Palette de commandes"
          >
            <div class="flex items-center gap-3 border-b border-line px-4">
              <app-icon name="search" class="text-subtle" />
              <input
                #paletteInput
                class="h-14 flex-1 !border-0 bg-transparent text-base !shadow-none outline-none"
                placeholder="Aller à une page ou lancer une action…"
                role="combobox"
                aria-controls="palette-results"
                aria-expanded="true"
                [attr.aria-activedescendant]="results().length ? 'palette-' + highlighted() : null"
                [ngModel]="query()"
                (ngModelChange)="query.set($event); highlighted.set(0)"
                (keydown)="onPaletteKey($event)"
              />
              <span class="kbd">Échap</span>
            </div>
            <ul
              #paletteList
              id="palette-results"
              class="palette-list relative max-h-[50vh] overflow-y-auto p-2"
              role="listbox"
              aria-label="Résultats"
            >
              @if (results().length > 0) {
                <li
                  class="palette-highlight"
                  aria-hidden="true"
                  [style.transform]="'translateY(' + highlightBox().top + 'px)'"
                  [style.height.px]="highlightBox().height"
                ></li>
              }
              @for (section of sections(); track section.group) {
                <li role="group" [attr.aria-labelledby]="'palette-group-' + $index">
                  <p class="palette-group" [id]="'palette-group-' + $index">{{ section.group }}</p>
                  <ul>
                    @for (item of section.items; track item.entry.label) {
                      <li
                        role="option"
                        class="palette-option"
                        [id]="'palette-' + item.index"
                        [attr.aria-selected]="item.index === highlighted()"
                        (mouseenter)="highlighted.set(item.index)"
                        (click)="run(item.entry)"
                      >
                        <app-icon [name]="item.entry.icon" [size]="18" />
                        <span class="flex-1 font-medium">{{ item.entry.label }}</span>
                        @if (item.index === highlighted()) {
                          <app-icon name="arrowRight" [size]="14" class="palette-go" />
                        }
                      </li>
                    }
                  </ul>
                </li>
              } @empty {
                <li class="empty-state">
                  <app-icon name="search" />
                  Aucun résultat pour « {{ query() }} »
                </li>
              }
            </ul>
            <p class="palette-foot" aria-hidden="true">
              <span><span class="kbd">↑</span><span class="kbd">↓</span> naviguer</span>
              <span><span class="kbd">Entrée</span> ouvrir</span>
              <span class="ml-auto">{{ results().length }} résultat(s)</span>
            </p>
          </div>
        </div>
      }
    </div>
    <!-- Loaded as soon as the browser is idle: ready before any lock, offline included -->
    @if (!auth.isPlatformOperator()) {
      @defer (on idle) {
        <app-lock-screen />
      }
    }
  `,
  styles: `
    :host {
      display: contents;
    }
    .nav-link {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      margin-top: 1px;
      padding: 0.5rem 0.75rem;
      border-radius: 0.6rem;
      font-size: 0.875rem;
      font-weight: 500;
      color: var(--text-muted);
      transition:
        background-color var(--motion-short) var(--ease-standard),
        color var(--motion-short) var(--ease-standard),
        transform var(--motion-short) var(--ease-emphasized);
    }
    .nav-link:hover {
      background: var(--surface-3);
      color: var(--text);
    }
    .nav-link:active {
      transform: scale(0.98);
    }
    .nav-link.nav-active {
      background: var(--brand-soft);
      color: var(--brand-text);
      font-weight: 600;
    }
    .brand-mark {
      position: relative;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2.5rem;
      height: 2.5rem;
      flex-shrink: 0;
      padding: 0.15rem;
      border-radius: 0.8rem;
      background: var(--brand-primary);
      color: var(--brand-on-primary);
      box-shadow: var(--shadow-sm);
    }
    .brand-mark:has(img) {
      background: #fff;
      box-shadow: inset 0 0 0 1px var(--border);
    }
    .brand-mark-edit {
      position: absolute;
      right: -0.3rem;
      bottom: -0.3rem;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1.2rem;
      height: 1.2rem;
      border: 2px solid var(--surface);
      border-radius: 999px;
      background: var(--text);
      color: var(--surface);
      opacity: 0;
      transform: scale(0.8);
      transition:
        opacity 120ms ease,
        transform 120ms ease;
    }
    .brand-mark:hover .brand-mark-edit,
    .brand-mark:focus-visible .brand-mark-edit {
      opacity: 1;
      transform: scale(1);
    }
    .popover {
      position: absolute;
      top: calc(100% + 0.5rem);
      z-index: 45;
      padding: 1rem;
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
      box-shadow: var(--shadow-lg);
    }
    .menu-item {
      display: flex;
      align-items: center;
      gap: 0.65rem;
      padding: 0.55rem 0.75rem;
      border-radius: 0.5rem;
      font-size: 0.875rem;
      text-align: left;
      color: var(--text);
    }
    .menu-item:hover {
      background: var(--surface-3);
    }
    .toggle {
      appearance: none;
      position: relative;
      width: 2.25rem !important;
      height: 1.3rem !important;
      border-radius: 999px;
      background: var(--border-strong);
      transition: background-color 150ms ease;
      cursor: pointer;
    }
    .toggle::after {
      content: '';
      position: absolute;
      top: 0.15rem;
      left: 0.15rem;
      width: 1rem;
      height: 1rem;
      border-radius: 999px;
      background: #fff;
      box-shadow: 0 1px 2px rgb(0 0 0 / 0.25);
      transition: transform 150ms ease;
    }
    .toggle:checked {
      background: var(--brand-primary);
    }
    .toggle:checked::after {
      transform: translateX(0.95rem);
    }
    .palette-backdrop {
      animation: fade-in var(--motion-medium) var(--ease-standard);
    }
    .palette-panel {
      animation: palette-in var(--motion-long) var(--ease-decelerate);
    }
    .palette-leave {
      animation: fade-out var(--motion-short) var(--ease-accelerate) forwards;
    }
    .palette-leave .palette-panel {
      animation: palette-out var(--motion-short) var(--ease-accelerate) forwards;
    }
    .palette-highlight {
      position: absolute;
      top: 0;
      left: 0.5rem;
      right: 0.5rem;
      border-radius: 0.6rem;
      background: var(--brand-soft);
      pointer-events: none;
      transition: transform var(--motion-medium) var(--ease-emphasized);
    }
    .palette-group {
      position: relative;
      padding: 0.6rem 0.75rem 0.3rem;
      font-size: 0.7rem;
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      color: var(--text-subtle);
    }
    .palette-option {
      position: relative;
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.6rem 0.75rem;
      border-radius: 0.6rem;
      font-size: 0.875rem;
      cursor: pointer;
      transition: color var(--motion-short) var(--ease-standard);
    }
    .palette-option[aria-selected='true'] {
      color: var(--brand-text);
    }
    .palette-go {
      animation: go-in var(--motion-medium) var(--ease-decelerate);
    }
    .palette-foot {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 0.55rem 1rem;
      border-top: 1px solid var(--border);
      background: var(--surface-2);
      font-size: 0.72rem;
      color: var(--text-subtle);
    }
    .palette-foot .kbd {
      margin-right: 0.2rem;
    }
    @keyframes palette-in {
      from {
        opacity: 0;
        transform: translateY(-10px) scale(0.97);
      }
    }
    @keyframes palette-out {
      to {
        opacity: 0;
        transform: translateY(-6px) scale(0.98);
      }
    }
    @keyframes fade-out {
      to {
        opacity: 0;
      }
    }
    @keyframes go-in {
      from {
        opacity: 0;
        transform: translateX(-6px);
      }
    }
    @media (max-width: 639px) {
      .palette-foot > :not(:last-child) {
        display: none;
      }
    }
  `,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly branding = inject(BrandingService);
  protected readonly connectivity = inject(ConnectivityService);
  protected readonly outbox = inject(SaleOutboxService);
  protected readonly license = inject(LicenseStateService);
  protected readonly idle = inject(IdleService);
  private readonly permissions = inject(PermissionService);
  protected readonly display = inject(DisplayPreferencesService);
  private readonly router = inject(Router);
  private readonly paletteInput = viewChild<ElementRef<HTMLInputElement>>('paletteInput');
  private readonly paletteList = viewChild<ElementRef<HTMLElement>>('paletteList');
  private readonly displayMenu = viewChild<ElementRef<HTMLElement>>('displayMenu');
  private readonly userMenu = viewChild<ElementRef<HTMLElement>>('userMenu');
  private readonly alertsBell = viewChild('alertsBell', { read: ViewContainerRef });

  protected readonly isMac = /Mac|iPhone|iPad/.test(navigator.platform);
  protected readonly canEditBranding = computed(() =>
    this.permissions.hasAny('CONFIG_BRANDING_MANAGE'),
  );
  protected readonly canSeeSubscription = computed(
    () => !this.auth.isPlatformOperator() && this.permissions.hasAny('SUBSCRIPTION_VIEW'),
  );
  protected readonly collapsed = signal(readCollapsed());
  protected readonly mobileOpen = signal(false);
  protected readonly expanded = computed(() => !this.collapsed() || this.mobileOpen());
  protected readonly menu = signal<'display' | 'user' | null>(null);
  protected readonly paletteOpen = signal(false);
  /** The palette plays its exit before it is removed. */
  protected readonly paletteClosing = signal(false);
  protected readonly query = signal('');
  protected readonly highlighted = signal(0);
  /** Position of the highlight sliding behind the selected palette entry. */
  protected readonly highlightBox = signal({ top: 0, height: 0 });
  private readonly url = signal(this.router.url);

  protected readonly densities: { value: Density; label: string }[] = [
    { value: 'COMPACT', label: 'Compacte' },
    { value: 'COMFORTABLE', label: 'Normale' },
    { value: 'LARGE', label: 'Grande' },
  ];
  protected readonly scales: { value: 1 | 1.15 | 1.3; label: string }[] = [
    { value: 1, label: 'A' },
    { value: 1.15, label: 'A+' },
    { value: 1.3, label: 'A++' },
  ];

  protected readonly nav = computed<NavGroup[]>(() =>
    this.auth.isPlatformOperator()
      ? PLATFORM_NAV
      : PHARMACY_NAV.map((group) => ({
          ...group,
          items: group.items.filter((item) => this.permissions.hasAny(...item.permissions)),
        })).filter((group) => group.items.length > 0),
  );

  protected readonly current = computed(() => {
    const path = this.url().split('?')[0];
    let best: { label: string; group: string; length: number } | null = null;
    for (const group of this.nav()) {
      for (const item of group.items) {
        if (
          (path === item.path || path.startsWith(item.path + '/')) &&
          (!best || item.path.length > best.length)
        ) {
          best = { label: item.label, group: group.label, length: item.path.length };
        }
      }
    }
    if (!best && path.startsWith('/account/subscription')) {
      return { label: 'Mon abonnement', group: 'Mon compte' };
    }
    if (!best && path.startsWith('/account')) {
      return { label: 'Mot de passe et PIN', group: 'Mon compte' };
    }
    return best;
  });

  protected readonly userName = computed(
    () => this.permissions.profile()?.displayName ?? this.auth.platformDisplayName() ?? '',
  );
  protected readonly roleLabel = computed(() => {
    if (this.auth.isPlatformOperator()) {
      return 'Opérateur éditeur';
    }
    const roles = this.permissions.profile()?.roles ?? [];
    return roles.map((r) => ROLE_LABELS[r] ?? r).join(', ') || this.branding.branding().displayName;
  });

  private readonly entries = computed<PaletteEntry[]>(() => {
    const pages = this.nav().flatMap((group) =>
      group.items.map((item) => ({
        label: item.label,
        group: group.label,
        icon: item.icon,
        run: () => void this.router.navigateByUrl(item.path),
      })),
    );
    const actions: PaletteEntry[] = [
      {
        label: this.display.preferences().darkMode
          ? 'Passer en mode clair'
          : 'Passer en mode sombre',
        group: 'Affichage',
        icon: this.display.preferences().darkMode ? 'sun' : 'moon',
        run: () => this.toggleTheme(null),
      },
      {
        label: this.display.preferences().highContrast
          ? 'Contraste normal'
          : 'Contraste élevé (soleil)',
        group: 'Affichage',
        icon: 'eye',
        run: () => this.display.update({ highContrast: !this.display.preferences().highContrast }),
      },
      {
        label: 'Déconnexion',
        group: 'Compte',
        icon: 'logout',
        run: () => void this.logout(),
      },
    ];
    if (!this.auth.isPlatformOperator()) {
      actions.unshift(
        {
          label: 'Verrouiller le poste',
          group: 'Compte',
          icon: 'lock',
          run: () => this.idle.lockNow(),
        },
        {
          label: 'Mot de passe et PIN',
          group: 'Compte',
          icon: 'key',
          run: () => void this.router.navigateByUrl('/account/password'),
        },
      );
    }
    return [...pages, ...actions];
  });

  protected readonly results = computed(() => {
    const q = normalize(this.query());
    const all = this.entries();
    return q ? all.filter((e) => normalize(`${e.label} ${e.group}`).includes(q)) : all;
  });

  /** Results grouped under their heading, each keeping its rank for the keyboard. */
  protected readonly sections = computed(() => {
    const sections: { group: string; items: { entry: PaletteEntry; index: number }[] }[] = [];
    this.results().forEach((entry, index) => {
      const last = sections[sections.length - 1];
      if (last?.group === entry.group) {
        last.items.push({ entry, index });
      } else {
        sections.push({ group: entry.group, items: [{ entry, index }] });
      }
    });
    return sections;
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    if (!this.auth.isPlatformOperator()) {
      this.license.start();
      this.idle.start(destroyRef);
    }
    // The palette highlight glides to the selected entry, which is kept in view
    effect(() => {
      const index = this.highlighted();
      const list = this.paletteList()?.nativeElement;
      this.results();
      if (!list) {
        return;
      }
      requestAnimationFrame(() => {
        const option = list.querySelector<HTMLElement>(`#palette-${index}`);
        if (option) {
          this.highlightBox.set({ top: option.offsetTop, height: option.offsetHeight });
          option.scrollIntoView({ block: 'nearest' });
        }
      });
    });
    // The internal control bell (shown to CONTROL_VIEW only) is loaded apart from the first screen
    afterNextRender(async () => {
      const { AlertsBell } = await import('./features/control/alerts-bell');
      this.alertsBell()?.createComponent(AlertsBell);
    });
    this.router.events
      .pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe((e) => {
        this.url.set(e.urlAfterRedirects);
        this.menu.set(null);
      });
  }

  protected initials(name: string): string {
    const words = name
      .replace(/^(Pharmacie|Dr\.?|M\.|Mme)\s+/i, '')
      .split(/\s+/)
      .filter(Boolean);
    return (words[0]?.[0] ?? 'P').toUpperCase() + (words[1]?.[0] ?? '').toUpperCase();
  }

  protected toggleCollapsed(): void {
    this.collapsed.update((value) => !value);
    try {
      localStorage.setItem(COLLAPSED_KEY, String(this.collapsed()));
    } catch {
      // storage unavailable: the choice lasts for the session
    }
  }

  protected toggleMenu(name: 'display' | 'user'): void {
    this.menu.update((open) => (open === name ? null : name));
  }

  /**
   * Night / day switch. Where view transitions exist, the new theme spreads in a circle from
   * the control that asked for it (top right when it came from the palette).
   */
  protected toggleTheme(event: Event | null): void {
    const apply = () => this.display.update({ darkMode: !this.display.preferences().darkMode });
    if (typeof document.startViewTransition !== 'function' || prefersReducedMotion()) {
      apply();
      return;
    }
    const origin = (event?.currentTarget as HTMLElement | undefined)?.getBoundingClientRect();
    const x = origin ? origin.left + origin.width / 2 : innerWidth - 120;
    const y = origin ? origin.top + origin.height / 2 : 32;
    const radius = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
    const root = document.documentElement;
    root.classList.add('theme-transition');
    const transition = document.startViewTransition(apply);
    transition.ready
      .then(() =>
        root.animate(
          { clipPath: [`circle(0 at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`] },
          {
            duration: DURATION.reveal,
            easing: EASING.emphasized,
            pseudoElement: '::view-transition-new(root)',
          },
        ),
      )
      .catch(() => undefined);
    const done = () => root.classList.remove('theme-transition');
    transition.finished.then(done, done);
  }

  protected setDensity(density: Density): void {
    this.display.update({ density });
  }

  protected setScale(fontScale: 1 | 1.15 | 1.3): void {
    this.display.update({ fontScale });
  }

  protected toggle(key: 'highContrast' | 'darkMode' | 'sounds', event: Event): void {
    if (key === 'darkMode') {
      this.toggleTheme(event);
      return;
    }
    this.display.update({ [key]: (event.target as HTMLInputElement).checked });
  }

  protected openPalette(): void {
    this.menu.set(null);
    this.query.set('');
    this.highlighted.set(0);
    this.paletteClosing.set(false);
    this.paletteOpen.set(true);
    setTimeout(() => this.paletteInput()?.nativeElement.focus());
  }

  protected closePalette(): void {
    if (!this.paletteOpen() || this.paletteClosing()) {
      return;
    }
    if (prefersReducedMotion()) {
      this.paletteOpen.set(false);
      return;
    }
    this.paletteClosing.set(true);
    setTimeout(() => {
      if (this.paletteClosing()) {
        this.paletteOpen.set(false);
        this.paletteClosing.set(false);
      }
    }, DURATION.short);
  }

  /** Leaves at once: the page transition that follows takes over. */
  protected run(entry: PaletteEntry): void {
    this.paletteOpen.set(false);
    this.mobileOpen.set(false);
    entry.run();
  }

  protected onPaletteKey(event: KeyboardEvent): void {
    const count = this.results().length;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.highlighted.update((i) => (count ? (i + 1) % count : 0));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.highlighted.update((i) => (count ? (i - 1 + count) % count : 0));
    } else if (event.key === 'Enter') {
      const entry = this.results()[this.highlighted()];
      if (entry) {
        this.run(entry);
      }
    }
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (
      (event.ctrlKey || event.metaKey) &&
      event.key.toLowerCase() === 'l' &&
      !this.auth.isPlatformOperator()
    ) {
      event.preventDefault();
      this.lock();
    } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      if (this.paletteOpen()) {
        this.closePalette();
      } else {
        this.openPalette();
      }
    } else if (event.key === 'Escape') {
      this.closePalette();
      this.menu.set(null);
      this.mobileOpen.set(false);
    }
  }

  protected onDocumentClick(event: MouseEvent): void {
    const target = event.target as Node;
    const open = this.menu();
    const container =
      open === 'display'
        ? this.displayMenu()?.nativeElement
        : open === 'user'
          ? this.userMenu()?.nativeElement
          : null;
    if (container && !container.contains(target)) {
      this.menu.set(null);
    }
  }

  protected lock(): void {
    this.menu.set(null);
    this.paletteOpen.set(false);
    this.idle.lockNow();
  }

  protected async logout(): Promise<void> {
    this.menu.set(null);
    await this.auth.logout();
    await this.router.navigateByUrl('/login');
  }
}

const ROLE_LABELS: Record<string, string> = {
  TITULAIRE: 'Pharmacien titulaire',
  ADJOINT: 'Pharmacien adjoint',
  PREPARATEUR: 'Préparateur',
  CAISSIER: 'Caissier',
  GESTIONNAIRE_STOCK: 'Gestionnaire de stock',
};

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === 'true';
  } catch {
    return false;
  }
}

function normalize(value: string): string {
  return value
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .trim();
}
