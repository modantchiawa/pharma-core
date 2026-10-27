import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ControlSettings, WebhookDelivery } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { CurrencyService } from '../../core/ui/currency.service';
import { ToastService } from '../../core/ui/toast.service';
import { Icon } from '../../shared/ui/icon';
import { errorDetail } from '../procurement/purchase-orders';
import { DAY_LABELS, HoursRow, activityDays, hoursRows } from './control-format';

interface Amounts {
  adjustment: string;
  refund: string;
  cancellation: string;
  alertAdjustment: string;
  alertCancellation: string;
  alertCash: string;
}

/**
 * Rules of the internal control, for the owner only (every save is audited): four-eyes
 * thresholds, alert thresholds, daily micro-count size, blind receiving and separation of
 * duties, activity hours, outgoing webhook.
 */
@Component({
  selector: 'app-control-settings-page',
  imports: [FormsModule, DatePipe, RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Règles du contrôle interne</h1>
          <p class="page-subtitle">
            Seuils, heures d’activité et alertes : réservé au titulaire, chaque modification est
            tracée
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <a class="btn btn-ghost" routerLink="/control"
            ><app-icon name="activity" [size]="16" />Contrôle interne</a
          >
          <button
            type="button"
            class="btn btn-primary"
            [disabled]="busy() || !settings()"
            (click)="save()"
          >
            <app-icon name="check" [size]="16" />
            Enregistrer
          </button>
        </div>
      </header>

      @if (settings(); as s) {
        <form class="grid items-start gap-5 xl:grid-cols-2" (ngSubmit)="save()">
          <section class="card">
            <div class="card-header">
              <div>
                <h2 class="card-title">Quatre yeux</h2>
                <p class="text-xs text-muted">
                  Au-delà de ces montants, une seconde personne habilitée saisit son PIN. Toujours
                  pour un stupéfiant. 0 = pas de seconde validation.
                </p>
              </div>
              <app-icon name="key" [size]="18" class="text-subtle" />
            </div>
            <div class="card-body grid gap-4 sm:grid-cols-3">
              <label class="block">
                <span class="label">Sorties et corrections de stock (au coût)</span>
                <input
                  class="input num"
                  inputmode="decimal"
                  name="adjustment"
                  [(ngModel)]="amounts.adjustment"
                />
              </label>
              <label class="block">
                <span class="label">Retours remboursés</span>
                <input
                  class="input num"
                  inputmode="decimal"
                  name="refund"
                  [(ngModel)]="amounts.refund"
                />
              </label>
              <label class="block">
                <span class="label">Annulation d’une vente payée</span>
                <input
                  class="input num"
                  inputmode="decimal"
                  name="cancellation"
                  [(ngModel)]="amounts.cancellation"
                />
              </label>
            </div>
          </section>

          <section class="card">
            <div class="card-header">
              <div>
                <h2 class="card-title">Alertes au titulaire</h2>
                <p class="text-xs text-muted">
                  Déclenchées dès ces montants ; 0 désactive l’alerte.
                </p>
              </div>
              <app-icon name="bell" [size]="18" class="text-subtle" />
            </div>
            <div class="card-body grid gap-4 sm:grid-cols-2">
              <label class="block">
                <span class="label">Sortie ou ajustement important</span>
                <input
                  class="input num"
                  inputmode="decimal"
                  name="alertAdjustment"
                  [(ngModel)]="amounts.alertAdjustment"
                />
              </label>
              <label class="block">
                <span class="label">Annulation après paiement</span>
                <input
                  class="input num"
                  inputmode="decimal"
                  name="alertCancellation"
                  [(ngModel)]="amounts.alertCancellation"
                />
              </label>
              <label class="block">
                <span class="label">Écart de caisse à la clôture</span>
                <input
                  class="input num"
                  inputmode="decimal"
                  name="alertCash"
                  [(ngModel)]="amounts.alertCash"
                />
              </label>
              <label class="block">
                <span class="label">Codes PIN erronés en 15 minutes</span>
                <input
                  class="input num"
                  type="number"
                  min="2"
                  max="10"
                  name="pin"
                  [(ngModel)]="s.pinFailureAlertCount"
                />
              </label>
              <p class="hint sm:col-span-2">
                Toujours signalés : écart d’inventaire sur un produit du coffre ou un stupéfiant,
                vol déclaré, vente ou sortie hors des heures d’activité (sauf garde).
              </p>
            </div>
          </section>

          <section class="card">
            <div class="card-header">
              <div>
                <h2 class="card-title">Contrôle du jour et réceptions</h2>
                <p class="text-xs text-muted">Recomptage tournant et séparation des tâches</p>
              </div>
              <app-icon name="counts" [size]="18" class="text-subtle" />
            </div>
            <div class="card-body space-y-4">
              <label class="block">
                <span class="label">Produits recomptés chaque jour</span>
                <input
                  class="input num w-28"
                  type="number"
                  min="5"
                  max="10"
                  name="size"
                  [(ngModel)]="s.microCountSize"
                />
                <span class="hint"
                  >Entre 5 et 10, tirés selon leur risque (valeur, coffre, écarts passés…)</span
                >
              </label>
              <label class="flex items-start justify-between gap-4">
                <span>
                  <span class="block text-sm font-medium">Réception à l’aveugle</span>
                  <span class="hint"
                    >Le réceptionnaire ne voit pas les quantités commandées ; l’écart est calculé
                    après la saisie.</span
                  >
                </span>
                <input
                  type="checkbox"
                  class="toggle mt-1"
                  name="blind"
                  [(ngModel)]="s.blindReceiving"
                />
              </label>
              <div>
                <span class="label">Celui qui a commandé réceptionne</span>
                <div class="segmented w-full" role="radiogroup">
                  @for (m of separations; track m.value) {
                    <button
                      type="button"
                      role="radio"
                      class="flex-1"
                      [attr.aria-checked]="s.receivingSeparation === m.value"
                      [attr.aria-selected]="s.receivingSeparation === m.value"
                      (click)="s.receivingSeparation = m.value"
                    >
                      {{ m.label }}
                    </button>
                  }
                </div>
              </div>
            </div>
          </section>

          <section class="card">
            <div class="card-header">
              <div>
                <h2 class="card-title">Heures d’activité</h2>
                <p class="text-xs text-muted">
                  En dehors, les ventes et sorties sont signalées, sauf nuits et jours de garde.
                </p>
              </div>
              <app-icon name="clock" [size]="18" class="text-subtle" />
            </div>
            <div class="card-body space-y-2">
              @for (row of hours(); track row.day) {
                <div class="hours-row">
                  <label class="flex min-w-28 items-center gap-2 text-sm font-medium">
                    <input type="checkbox" [name]="'open-' + row.day" [(ngModel)]="row.open" />
                    {{ dayLabel(row.day) }}
                  </label>
                  @if (row.open) {
                    <input
                      class="input w-28"
                      type="time"
                      [name]="'from-' + row.day"
                      [(ngModel)]="row.from"
                      [attr.aria-label]="'Ouverture ' + dayLabel(row.day)"
                    />
                    <span class="text-subtle">à</span>
                    <input
                      class="input w-28"
                      type="time"
                      [name]="'to-' + row.day"
                      [(ngModel)]="row.to"
                      [attr.aria-label]="'Fermeture ' + dayLabel(row.day)"
                    />
                  } @else {
                    <span class="text-sm text-subtle">fermé</span>
                  }
                </div>
              }
            </div>
          </section>

          <section class="card xl:col-span-2">
            <div class="card-header">
              <div>
                <h2 class="card-title">Webhook d’alertes</h2>
                <p class="text-xs text-muted">
                  Chaque alerte est envoyée en JSON (POST) signé HMAC-SHA256, à relier à WhatsApp ou
                  aux SMS par le service de votre choix. Livrée plus tard si le serveur est hors
                  ligne.
                </p>
              </div>
              <input
                type="checkbox"
                class="toggle"
                name="webhook"
                aria-label="Activer le webhook"
                [(ngModel)]="s.webhookEnabled"
              />
            </div>
            <div class="card-body grid gap-4 lg:grid-cols-2">
              <label class="block">
                <span class="label">Adresse (URL)</span>
                <input
                  class="input"
                  type="url"
                  name="url"
                  placeholder="https://…"
                  [(ngModel)]="s.webhookUrl"
                />
              </label>
              <label class="block">
                <span class="label"
                  >Secret partagé {{ s.webhookSecretSet ? '(enregistré)' : '' }}</span
                >
                <input
                  class="input"
                  type="password"
                  autocomplete="new-password"
                  name="secret"
                  [placeholder]="
                    s.webhookSecretSet ? 'Laisser vide pour le conserver' : '16 caractères au moins'
                  "
                  [(ngModel)]="secret"
                />
              </label>
              <div class="hint lg:col-span-2">
                En-têtes : <code>X-PharmaCore-Signature: sha256=…</code> calculé sur
                <code>horodatage + "." + corps</code>, <code>X-PharmaCore-Timestamp</code>,
                <code>X-PharmaCore-Delivery</code>. Aucune donnée client ou patient n’est envoyée.
              </div>
              <div class="flex flex-wrap items-center gap-2 lg:col-span-2">
                <button
                  type="button"
                  class="btn btn-secondary btn-sm"
                  [disabled]="busy()"
                  (click)="test()"
                >
                  <app-icon name="send" [size]="14" />Envoyer une alerte de test
                </button>
                <button type="button" class="btn btn-ghost btn-sm" (click)="loadDeliveries()">
                  <app-icon name="refresh" [size]="14" />Actualiser
                </button>
              </div>
              @if (deliveries().length > 0) {
                <div class="overflow-x-auto lg:col-span-2">
                  <table class="data-table whitespace-nowrap">
                    <thead>
                      <tr>
                        <th>Créée</th>
                        <th>État</th>
                        <th class="text-right">Essais</th>
                        <th>Dernière erreur</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (d of deliveries(); track d.id) {
                        <tr>
                          <td class="tabular-nums">{{ d.createdAt | date: 'dd/MM HH:mm' }}</td>
                          <td>
                            <span
                              class="badge"
                              [class.badge-success]="d.status === 'DELIVERED'"
                              [class.badge-warning]="d.status === 'PENDING'"
                              [class.badge-danger]="d.status === 'FAILED'"
                            >
                              {{
                                d.status === 'DELIVERED'
                                  ? 'livrée'
                                  : d.status === 'PENDING'
                                    ? 'en attente'
                                    : 'abandonnée'
                              }}
                            </span>
                          </td>
                          <td class="num">{{ d.attempts }}</td>
                          <td class="text-xs text-muted">{{ d.lastError ?? '—' }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </div>
          </section>
        </form>
        @if (s.updatedAt) {
          <p class="mt-4 text-xs text-subtle">
            Dernière modification le {{ s.updatedAt | date: 'd MMM yyyy, HH:mm' }}
          </p>
        }
      } @else {
        <div class="skeleton h-64"></div>
      }
    </div>
  `,
  styles: `
    .hours-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem 0.75rem;
      padding: 0.35rem 0;
    }
    .hours-row + .hours-row {
      border-top: 1px solid var(--border);
    }
    .toggle {
      appearance: none;
      position: relative;
      width: 2.25rem;
      height: 1.3rem;
      flex-shrink: 0;
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
    code {
      padding: 0.05rem 0.3rem;
      border-radius: 0.3rem;
      background: var(--surface-3);
      font-size: 0.75rem;
    }
  `,
})
export class ControlSettingsPage {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly currency = inject(CurrencyService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/control`;

  protected readonly separations: {
    value: ControlSettings['receivingSeparation'];
    label: string;
  }[] = [
    { value: 'OFF', label: 'Autorisé' },
    { value: 'WARN', label: 'Alerte' },
    { value: 'BLOCK', label: 'Interdit' },
  ];
  protected readonly settings = signal<ControlSettings | null>(null);
  protected readonly hours = signal<HoursRow[]>([]);
  protected readonly deliveries = signal<WebhookDelivery[]>([]);
  protected readonly busy = signal(false);
  protected amounts: Amounts = {
    adjustment: '',
    refund: '',
    cancellation: '',
    alertAdjustment: '',
    alertCancellation: '',
    alertCash: '',
  };
  protected secret = '';

  constructor() {
    void this.load();
    void this.loadDeliveries();
  }

  protected dayLabel(day: HoursRow['day']): string {
    return DAY_LABELS[day];
  }

  private async load(): Promise<void> {
    try {
      this.apply(await firstValueFrom(this.http.get<ControlSettings>(`${this.api}/settings`)));
    } catch (error) {
      this.toast.error(errorDetail(error, 'Réglages indisponibles'));
    }
  }

  private apply(s: ControlSettings): void {
    const units = (minor: number) => String(this.currency.toUnits(minor));
    this.amounts = {
      adjustment: units(s.adjustmentApprovalCents),
      refund: units(s.refundApprovalCents),
      cancellation: units(s.cancellationApprovalCents),
      alertAdjustment: units(s.alertAdjustmentCents),
      alertCancellation: units(s.alertCancellationCents),
      alertCash: units(s.alertCashVarianceCents),
    };
    this.hours.set(hoursRows(s.activityHours));
    this.secret = '';
    this.settings.set({ ...s });
  }

  protected async loadDeliveries(): Promise<void> {
    try {
      this.deliveries.set(
        await firstValueFrom(this.http.get<WebhookDelivery[]>(`${this.api}/webhook/deliveries`)),
      );
    } catch {
      this.deliveries.set([]);
    }
  }

  protected async save(): Promise<void> {
    const s = this.settings();
    if (!s || this.busy()) {
      return;
    }
    const { days, problem } = activityDays(this.hours());
    if (problem) {
      this.toast.error(problem);
      return;
    }
    const parse = (value: string) => this.currency.parse(value);
    this.busy.set(true);
    try {
      const saved = await firstValueFrom(
        this.http.put<ControlSettings>(`${this.api}/settings`, {
          microCountSize: Number(s.microCountSize),
          adjustmentApprovalCents: parse(this.amounts.adjustment),
          refundApprovalCents: parse(this.amounts.refund),
          cancellationApprovalCents: parse(this.amounts.cancellation),
          alertAdjustmentCents: parse(this.amounts.alertAdjustment),
          alertCancellationCents: parse(this.amounts.alertCancellation),
          alertCashVarianceCents: parse(this.amounts.alertCash),
          pinFailureAlertCount: Number(s.pinFailureAlertCount),
          blindReceiving: s.blindReceiving,
          receivingSeparation: s.receivingSeparation,
          activityHours: days,
          webhookEnabled: s.webhookEnabled,
          webhookUrl: s.webhookUrl?.trim() || null,
          webhookSecret: this.secret.trim() || null,
          version: s.version,
        }),
      );
      this.apply(saved);
      this.toast.success('Règles du contrôle interne enregistrées');
    } catch (error) {
      this.toast.error(errorDetail(error, 'Réglages refusés'));
    } finally {
      this.busy.set(false);
    }
  }

  protected async test(): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post(`${this.api}/alerts/test`, null));
      this.toast.success(
        'Alerte de test créée : elle apparaît dans la cloche et part vers le webhook',
      );
      await this.loadDeliveries();
    } catch (error) {
      this.toast.error(errorDetail(error, 'Alerte de test refusée'));
    } finally {
      this.busy.set(false);
    }
  }
}
