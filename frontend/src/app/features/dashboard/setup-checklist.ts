import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { PublicSharing } from '../../core/api/models';
import { PermissionService } from '../../core/auth/permission.service';
import { BrandingService, DEFAULT_BRANDING } from '../../core/branding/branding.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { Icon } from '../../shared/ui/icon';
import { IconName } from '../../shared/ui/icons';

const DISMISSED_KEY = 'pharmacore.setup.dismissed';

interface SetupStep {
  icon: IconName;
  title: string;
  text: string;
  link: string;
  fragment?: string;
  done: boolean;
}

/**
 * First steps for the owner on the dashboard: logo, colors, public directory. Each step ticks
 * itself once done; the card goes away when everything is done or when it is hidden.
 */
@Component({
  selector: 'app-setup-checklist',
  imports: [RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Hidden host: no empty slot in the page's vertical rhythm once the card is gone
  host: { class: 'block', '[class.hidden]': '!visible()' },
  template: `
    @if (visible()) {
      <section class="card setup overflow-hidden" aria-labelledby="setup-title">
        <div class="card-header">
          <div class="flex min-w-0 items-center gap-3">
            <span class="kpi-icon"><app-icon name="sparkles" [size]="16" /></span>
            <div class="min-w-0">
              <h2 id="setup-title" class="card-title">Mise en route de la pharmacie</h2>
              <p class="text-xs text-muted">
                {{ doneCount() }} étape(s) sur {{ steps().length }} · quelques minutes pour une
                application à votre image
              </p>
            </div>
          </div>
          <button type="button" class="btn btn-ghost btn-sm" (click)="dismiss()">Masquer</button>
        </div>
        <div class="progress" aria-hidden="true">
          <span [style.width.%]="(doneCount() / steps().length) * 100"></span>
        </div>
        <ol class="grid gap-px bg-[var(--border)] sm:grid-cols-3">
          @for (step of steps(); track step.title) {
            <li class="bg-surface">
              <a
                [routerLink]="step.link"
                [fragment]="step.fragment"
                class="step"
                [class.is-done]="step.done"
              >
                <span class="step-check">
                  <app-icon [name]="step.done ? 'check' : step.icon" [size]="16" />
                </span>
                <span class="min-w-0 flex-1">
                  <span class="block text-sm font-semibold">{{ step.title }}</span>
                  <span class="block text-xs text-muted">{{ step.text }}</span>
                </span>
                @if (!step.done) {
                  <app-icon name="chevronRight" [size]="16" class="text-subtle" />
                }
              </a>
            </li>
          }
        </ol>
      </section>
    }
  `,
  styles: `
    .progress {
      height: 3px;
      background: var(--surface-3);
    }
    .progress > span {
      display: block;
      height: 100%;
      background: var(--brand-primary);
      transition: width 300ms ease;
    }
    .step {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      height: 100%;
      padding: calc(0.9rem * var(--density)) calc(1.25rem * var(--density));
      transition: background-color 120ms ease;
    }
    .step:hover {
      background: var(--surface-2);
    }
    .step-check {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2rem;
      height: 2rem;
      flex-shrink: 0;
      border: 1.5px dashed var(--border-strong);
      border-radius: 999px;
      color: var(--text-muted);
    }
    .step.is-done .step-check {
      border: 0;
      background: var(--success-soft);
      color: var(--success);
    }
    .step.is-done .text-sm {
      color: var(--text-muted);
      text-decoration: line-through;
    }
  `,
})
export class SetupChecklist {
  private readonly branding = inject(BrandingService);
  private readonly permissions = inject(PermissionService);
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly listed = signal<boolean | null>(null);
  private readonly dismissed = signal(readDismissed());

  protected readonly steps = computed<SetupStep[]>(() => [
    {
      icon: 'image',
      title: 'Ajouter votre logo',
      text: 'Barre latérale, connexion, tickets',
      link: '/admin/branding',
      fragment: 'logo',
      done: this.branding.hasLogo(),
    },
    {
      icon: 'branding',
      title: 'Choisir vos couleurs',
      text: 'Boutons et en-têtes à vos couleurs',
      link: '/admin/branding',
      done:
        this.branding.branding().primaryColor.toUpperCase() !==
        DEFAULT_BRANDING.primaryColor.toUpperCase(),
    },
    {
      icon: 'globe',
      title: 'Apparaître dans l’annuaire',
      text: 'Les patients vous trouvent en ligne',
      link: '/admin/branding',
      done: this.listed() === true,
    },
  ]);
  protected readonly doneCount = computed(() => this.steps().filter((s) => s.done).length);
  protected readonly visible = computed(
    () =>
      !this.dismissed() &&
      this.permissions.hasAny('CONFIG_BRANDING_MANAGE') &&
      this.listed() !== null &&
      this.doneCount() < this.steps().length,
  );

  constructor() {
    if (!this.dismissed() && this.permissions.hasAny('CONFIG_BRANDING_MANAGE')) {
      void this.loadListing();
    }
  }

  protected dismiss(): void {
    this.dismissed.set(true);
    try {
      localStorage.setItem(DISMISSED_KEY, '1');
    } catch {
      // Hidden for this session only
    }
  }

  private async loadListing(): Promise<void> {
    try {
      const sharing = await firstValueFrom(
        this.http.get<PublicSharing>(`${this.env.apiUrl}/v1/config/public-sharing`),
      );
      this.listed.set(sharing.publicListing);
    } catch {
      // Offline: the card waits for the next visit rather than showing a wrong state
    }
  }
}

function readDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISSED_KEY) === '1';
  } catch {
    return false;
  }
}
