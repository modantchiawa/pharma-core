import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  IssuedIngestToken,
  MOBILE_MONEY_PROVIDERS,
  PaymentChannelSettings,
  PaymentNotification,
  PaymentNotificationFeed,
  PendingPayment,
} from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { NodeInfoService } from '../../core/api/node-info.service';
import { PaymentNotificationsService } from '../../core/payments/payment-notifications.service';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';

type PaymentStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED';

const STATUS_LABELS: Record<PaymentStatus, string> = {
  PENDING: 'En attente du relevé',
  CONFIRMED: 'Confirmés',
  REJECTED: 'Rejetés',
};

/** Mobile money and card receipts ticked against the operator's statement. */
@Component({
  selector: 'app-payments-page',
  imports: [FormsModule, DatePipe, MoneyPipe, Icon, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Paiements</h1>
          <p class="page-subtitle">
            Pointez les encaissements mobile money et carte sur le relevé de l'opérateur.
          </p>
        </div>
        <button type="button" class="btn btn-secondary" (click)="load()">
          <app-icon name="refresh" [size]="16" />
          Actualiser
        </button>
      </header>

      <div class="mb-5 grid gap-4 sm:grid-cols-2">
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="mobileMoney" [size]="16" /></span>Paiements
          </div>
          <div class="kpi-value">{{ payments().length }}</div>
          <div class="kpi-foot">{{ statusLabels[status] }}</div>
        </div>
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="banknote" [size]="16" /></span>Montant total
          </div>
          <div class="kpi-value">{{ total() | money }}</div>
          <div class="kpi-foot">{{ provider || 'Tous (mobile money et carte)' }}</div>
        </div>
      </div>

      @if (message(); as text) {
        <div class="alert alert-info mb-4" role="status">
          <app-icon name="info" [size]="18" />
          <span>{{ text }}</span>
        </div>
      }

      <section class="card mb-5" data-testid="payment-confirmations">
        <div class="card-header flex-wrap">
          <div class="flex items-center gap-2">
            <app-icon name="mobileMoney" [size]="18" class="text-brand" />
            <h2 class="card-title">Confirmations reçues</h2>
            @if (notifications.openCount() > 0) {
              <span class="badge badge-brand">{{ notifications.openCount() }} à rattacher</span>
            }
            @if (notifications.queued() > 0) {
              <span class="badge badge-warning"
                >{{ notifications.queued() }} saisie(s) en attente du serveur</span
              >
            }
          </div>
          <div class="segmented" role="tablist" aria-label="Confirmations">
            <button
              type="button"
              role="tab"
              [attr.aria-selected]="feedStatus() === 'UNMATCHED'"
              (click)="showFeed('UNMATCHED')"
            >
              À rattacher
            </button>
            <button
              type="button"
              role="tab"
              [attr.aria-selected]="feedStatus() === 'ALL'"
              (click)="showFeed('ALL')"
            >
              Toutes
            </button>
          </div>
        </div>
        <p class="border-b px-5 py-2 text-xs text-muted">
          SMS de l'opérateur transférés par le téléphone de la pharmacie, webhooks, ou saisies au
          comptoir : chaque confirmation est diffusée à tous les postes sur le réseau de l'officine,
          sans Internet, et rattachée d'elle-même au règlement en attente du même montant et du même
          opérateur quand il est unique.
        </p>
        @if (feed().length > 0) {
          <div class="overflow-x-auto">
            <table class="data-table whitespace-nowrap">
              <thead>
                <tr>
                  <th>Reçue</th>
                  <th>Source</th>
                  <th>Opérateur</th>
                  <th class="text-right">Montant</th>
                  <th>Référence</th>
                  <th>État</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                @for (n of feed(); track n.id) {
                  <tr>
                    <td class="tabular-nums text-muted">
                      {{ n.receivedAt | date: 'dd/MM HH:mm' }}
                    </td>
                    <td>{{ sourceLabel(n.source) }}</td>
                    <td>{{ (n.provider ?? '—').replace('_', ' ') }}</td>
                    <td class="num font-medium">
                      {{ n.amountCents === null ? '—' : (n.amountCents | money) }}
                    </td>
                    <td class="font-mono text-xs">
                      {{ n.reference ?? '—' }}
                      @if (n.payerPhone) {
                        <span class="text-subtle">· {{ n.payerPhone }}</span>
                      }
                    </td>
                    <td>
                      @if (n.status === 'MATCHED') {
                        <span class="badge badge-success">
                          <app-icon name="check" [size]="12" />{{ n.ticketNumber ?? 'rattachée' }}
                        </span>
                      } @else if (n.status === 'IGNORED') {
                        <span class="badge">{{ n.incoming ? 'ignorée' : 'sortant / solde' }}</span>
                      } @else {
                        <span class="badge badge-warning">à rattacher</span>
                      }
                    </td>
                    <td>
                      @if (n.status === 'UNMATCHED') {
                        <div class="flex items-center justify-end gap-1">
                          <select
                            class="select h-9 w-56"
                            [ngModel]="claimTarget()[n.id] ?? ''"
                            (ngModelChange)="pickTarget(n.id, $event)"
                            [name]="'claim-' + n.id"
                            aria-label="Règlement à confirmer"
                          >
                            <option value="">Règlement en attente…</option>
                            @for (p of claimCandidates(n); track p.paymentId) {
                              <option [value]="p.paymentId">
                                {{ p.ticketNumber }} · {{ p.amountCents | money }} ·
                                {{ p.provider }}
                              </option>
                            }
                          </select>
                          <button
                            type="button"
                            class="btn btn-secondary btn-sm"
                            [disabled]="!claimTarget()[n.id]"
                            (click)="claim(n)"
                          >
                            Rattacher
                          </button>
                          <button
                            type="button"
                            class="btn btn-ghost btn-sm text-muted"
                            (click)="ignore(n)"
                            *appHasPermission="'PAYMENT_RECONCILE'"
                          >
                            Ignorer
                          </button>
                        </div>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <div class="empty-state">
            <app-icon name="mobileMoney" [size]="22" />
            <p>Aucune confirmation {{ feedStatus() === 'UNMATCHED' ? 'à rattacher' : 'reçue' }}</p>
          </div>
        }
      </section>

      <section class="card mb-5" *appHasPermission="'PAYMENT_RECONCILE'">
        <div class="card-header">
          <div class="flex items-center gap-2">
            <app-icon name="settings" [size]="18" class="text-muted" />
            <h2 class="card-title">Réception des confirmations</h2>
          </div>
          <button type="button" class="btn btn-secondary btn-sm" (click)="issueToken()">
            <app-icon name="key" [size]="14" />
            {{ channel()?.tokenSet ? 'Nouveau jeton' : 'Créer le jeton' }}
          </button>
        </div>
        <div class="card-body space-y-3 text-sm">
          @if (issued(); as token) {
            <div class="alert alert-warning" role="alert">
              <app-icon name="key" [size]="18" />
              <span>
                Jeton à configurer sur le téléphone (affiché une seule fois) :
                <code class="font-mono font-bold select-all">{{ token.token }}</code>
              </span>
            </div>
          } @else if (channel()?.tokenSet) {
            <p class="text-muted">
              Jeton en place (se termine par <code>{{ channel()?.tokenHint }}</code
              >), créé le {{ channel()?.tokenCreatedAt | date: 'dd/MM/yyyy' }}.
            </p>
          } @else {
            <p class="text-muted">
              Aucun jeton : créez-en un pour brancher le téléphone ou les webhooks.
            </p>
          }
          <div class="rounded-[var(--radius)] border bg-surface-2 p-3">
            <p class="font-medium">Brancher le téléphone qui reçoit les SMS mobile money</p>
            <ol class="mt-1 list-decimal space-y-0.5 pl-5 text-xs text-muted">
              <li>
                Installer une application de transfert de SMS sur le téléphone (gratuite, « SMS
                forwarder »).
              </li>
              <li>
                Règle : les SMS des opérateurs sont envoyés en POST (JSON ou formulaire, champs
                <code>from</code> et <code>text</code>) à
                <code class="select-all">{{ smsEndpoint() }}</code>
              </li>
              <li>
                En-tête <code>X-PharmaCore-Token</code> (ou paramètre <code>token</code>) : le jeton
                ci-dessus.
              </li>
              <li>
                Le téléphone doit être sur le Wi-Fi de l'officine : le serveur local suffit, sans
                Internet.
              </li>
            </ol>
            <p class="mt-2 text-xs text-muted">
              Webhook opérateur (quand l'opérateur en propose un) : même jeton, adresse
              <code class="select-all">{{ webhookEndpoint() }}</code
              >.
            </p>
          </div>
          @if (channelForm; as form) {
            <form
              class="grid gap-3 sm:grid-cols-[1fr_auto_auto] sm:items-end"
              (ngSubmit)="saveChannel()"
            >
              <label class="flex items-center gap-2">
                <input type="checkbox" [(ngModel)]="form.autoConfirm" name="autoConfirm" />
                Confirmer d'elle-même le règlement unique de même montant et opérateur
              </label>
              <label class="block">
                <span class="label">Fenêtre (heures)</span>
                <input
                  type="number"
                  class="input w-28"
                  min="1"
                  max="720"
                  [(ngModel)]="form.matchWindowHours"
                  name="window"
                />
              </label>
              <button type="submit" class="btn btn-primary">Enregistrer</button>
              <div class="sm:col-span-3">
                <span class="label"
                  >Motifs propres à la pharmacie (facultatif, groupes nommés amount, reference,
                  phone)</span
                >
                @for (pattern of form.patterns; track $index) {
                  <div class="mt-1 flex gap-2">
                    <select
                      class="select w-44"
                      [(ngModel)]="pattern.provider"
                      [name]="'pp-' + $index"
                    >
                      <option [ngValue]="null">Tout opérateur</option>
                      @for (p of providers; track p) {
                        <option [value]="p">{{ p }}</option>
                      }
                    </select>
                    <input
                      class="input font-mono text-xs"
                      [(ngModel)]="pattern.regex"
                      [name]="'pr-' + $index"
                      placeholder="Credit de (?<amount>[0-9 ]+) F.*code (?<reference>[A-Z0-9]+)"
                    />
                    <button
                      type="button"
                      class="btn btn-ghost btn-icon"
                      (click)="form.patterns.splice($index, 1)"
                      aria-label="Retirer"
                    >
                      <app-icon name="x" [size]="14" />
                    </button>
                  </div>
                }
                <button
                  type="button"
                  class="btn btn-ghost btn-sm mt-1"
                  (click)="form.patterns.push({ provider: null, regex: '' })"
                >
                  <app-icon name="plus" [size]="14" />
                  Ajouter un motif
                </button>
              </div>
            </form>
          }
        </div>
      </section>

      <div class="card">
        <div class="card-header flex-wrap">
          <div
            class="segmented max-w-full overflow-x-auto [&>button]:whitespace-nowrap"
            role="tablist"
            aria-label="État"
          >
            @for (option of statusOptions; track option) {
              <button
                type="button"
                role="tab"
                [attr.aria-selected]="status === option"
                (click)="status = option; load()"
              >
                {{ statusLabels[option] }}
              </button>
            }
          </div>
          <label class="flex w-full items-center gap-2 text-sm sm:w-auto">
            <span class="text-muted">Opérateur</span>
            <select class="select sm:w-64" [(ngModel)]="provider" name="provider" (change)="load()">
              <option value="">Tous (mobile money et carte)</option>
              @for (p of providers; track p) {
                <option [value]="p">{{ p }}</option>
              }
            </select>
          </label>
        </div>
        @if (payments().length > 0) {
          <div class="overflow-x-auto">
            <table class="data-table whitespace-nowrap">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Ticket</th>
                  <th>Mode</th>
                  <th>Référence</th>
                  <th class="text-right">Montant</th>
                  <th class="text-right">Relevé</th>
                </tr>
              </thead>
              <tbody>
                @for (p of payments(); track p.paymentId) {
                  <tr>
                    <td class="whitespace-nowrap tabular-nums text-muted">
                      {{ p.occurredAt | date: 'dd/MM HH:mm' }}
                    </td>
                    <td class="font-medium">{{ p.ticketNumber }}</td>
                    <td>
                      <span class="inline-flex items-center gap-1.5">
                        <app-icon
                          [name]="p.method === 'CARD' ? 'card' : 'mobileMoney'"
                          [size]="15"
                          class="text-subtle"
                        />
                        {{ p.provider ?? p.method }}
                      </span>
                    </td>
                    <td class="font-mono text-xs">{{ p.reference ?? '—' }}</td>
                    <td class="num font-medium">{{ p.amountCents | money }}</td>
                    <td>
                      <div class="flex items-center justify-end gap-1">
                        @if (p.status === 'PENDING') {
                          <button
                            type="button"
                            class="btn btn-secondary btn-sm"
                            (click)="confirm(p)"
                          >
                            <app-icon name="check" [size]="16" class="text-success" />
                            Sur le relevé
                          </button>
                          <button
                            type="button"
                            class="btn btn-ghost btn-sm text-danger"
                            (click)="reject(p)"
                          >
                            <app-icon name="x" [size]="16" />
                            Absent
                          </button>
                        } @else if (p.status === 'REJECTED') {
                          <span class="badge badge-danger">
                            <app-icon name="x" [size]="12" />{{ p.rejectionReason }}
                          </span>
                        } @else if (p.status === 'CONFIRMED') {
                          <span class="badge badge-success">
                            <app-icon name="check" [size]="12" />Confirmé
                          </span>
                        }
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <div class="empty-state">
            <app-icon name="mobileMoney" [size]="22" />
            <p>Aucun paiement</p>
          </div>
        }
      </div>
    </div>
  `,
})
export class PaymentsPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/payments`;

  protected readonly providers = MOBILE_MONEY_PROVIDERS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly statusOptions = Object.keys(STATUS_LABELS) as PaymentStatus[];
  protected readonly payments = signal<PendingPayment[]>([]);
  protected readonly message = signal<string | null>(null);
  protected provider = '';
  protected status: PaymentStatus = 'PENDING';

  protected readonly notifications = inject(PaymentNotificationsService);
  private readonly toasts = inject(ToastService);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly node = inject(NodeInfoService);
  protected readonly feedStatus = signal<'UNMATCHED' | 'ALL'>('UNMATCHED');
  protected readonly feed = signal<PaymentNotification[]>([]);
  protected readonly pendingForClaim = signal<PendingPayment[]>([]);
  protected readonly claimTarget = signal<Record<string, string>>({});
  protected readonly channel = signal<PaymentChannelSettings | null>(null);
  protected readonly issued = signal<IssuedIngestToken | null>(null);
  protected channelForm: PaymentChannelSettings['values'] | null = null;
  protected readonly smsEndpoint = computed(() => this.endpoint('sms'));
  protected readonly webhookEndpoint = computed(() => this.endpoint('webhook'));

  constructor() {
    void this.load();
    this.notifications.start();
    void this.showFeed('UNMATCHED');
    void this.loadChannel();
    this.notifications.arrivals.pipe(takeUntilDestroyed()).subscribe(() => {
      void this.showFeed(this.feedStatus());
      void this.load();
    });
  }

  private endpoint(kind: 'sms' | 'webhook'): string {
    const slug = this.node.info()?.pharmacySlug ?? '<identifiant-de-la-pharmacie>';
    const base = this.env.apiUrl.startsWith('http')
      ? this.env.apiUrl
      : `${location.origin}${this.env.apiUrl}`;
    return `${base}/public/payments/${slug}/${kind}`;
  }

  protected sourceLabel(source: PaymentNotification['source']): string {
    return { SMS: 'SMS du téléphone', WEBHOOK: 'Webhook', MANUAL: 'Saisie au comptoir' }[source];
  }

  protected async showFeed(status: 'UNMATCHED' | 'ALL'): Promise<void> {
    this.feedStatus.set(status);
    try {
      const result = await firstValueFrom(
        this.http.get<PaymentNotificationFeed>(`${this.api}/notifications`, {
          params: status === 'ALL' ? { limit: 200 } : { status, limit: 200 },
        }),
      );
      this.feed.set(result.notifications);
      if (result.notifications.some((n) => n.status === 'UNMATCHED')) {
        this.pendingForClaim.set(
          await firstValueFrom(
            this.http.get<PendingPayment[]>(`${this.api}/pending`, {
              params: { status: 'PENDING' },
            }),
          ),
        );
      }
    } catch {
      this.feed.set([]);
    }
  }

  /** Pending payments of the same amount first, then the rest, so the right one is one click away. */
  protected claimCandidates(notification: PaymentNotification): PendingPayment[] {
    const pending = this.pendingForClaim().filter((p) => p.method === 'MOBILE_MONEY');
    return [...pending].sort((a, b) => {
      const sameA = a.amountCents === notification.amountCents ? 0 : 1;
      const sameB = b.amountCents === notification.amountCents ? 0 : 1;
      return sameA - sameB || b.occurredAt.localeCompare(a.occurredAt);
    });
  }

  protected pickTarget(notificationId: string, paymentId: string): void {
    this.claimTarget.update((map) => ({ ...map, [notificationId]: paymentId }));
  }

  protected async claim(notification: PaymentNotification): Promise<void> {
    const paymentId = this.claimTarget()[notification.id];
    if (!paymentId) {
      return;
    }
    try {
      await firstValueFrom(
        this.http.post(`${this.api}/notifications/${notification.id}/claim`, { paymentId }),
      );
      this.notifications.consume(notification.id);
      this.toasts.success('Règlement confirmé par la notification reçue');
      await this.showFeed(this.feedStatus());
      await this.load();
    } catch (error) {
      this.message.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Rattachement refusé',
      );
    }
  }

  protected async ignore(notification: PaymentNotification): Promise<void> {
    try {
      await firstValueFrom(
        this.http.post(`${this.api}/notifications/${notification.id}/ignore`, {}),
      );
      this.notifications.consume(notification.id);
      await this.showFeed(this.feedStatus());
    } catch (error) {
      this.message.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Action refusée',
      );
    }
  }

  private async loadChannel(): Promise<void> {
    try {
      const settings = await firstValueFrom(
        this.http.get<PaymentChannelSettings>(`${this.api}/channel`),
      );
      this.channel.set(settings);
      this.channelForm = {
        ...settings.values,
        patterns: settings.values.patterns.map((p) => ({ ...p })),
      };
    } catch {
      this.channel.set(null);
    }
  }

  protected async issueToken(): Promise<void> {
    if (
      this.channel()?.tokenSet &&
      !confirm(
        "Un nouveau jeton remplace l'ancien : le téléphone et les webhooks devront être mis à jour. Continuer ?",
      )
    ) {
      return;
    }
    try {
      this.issued.set(
        await firstValueFrom(this.http.post<IssuedIngestToken>(`${this.api}/channel/token`, {})),
      );
      await this.loadChannel();
    } catch {
      this.message.set('Création du jeton impossible');
    }
  }

  protected async saveChannel(): Promise<void> {
    if (!this.channelForm) {
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.put<PaymentChannelSettings>(`${this.api}/channel`, {
          ...this.channelForm,
          patterns: this.channelForm.patterns.filter((p) => p.regex.trim()),
        }),
      );
      this.channel.set(saved);
      this.toasts.success('Réglages enregistrés');
    } catch (error) {
      this.message.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Réglages refusés',
      );
    }
  }

  protected total(): number {
    return this.payments().reduce((sum, p) => sum + p.amountCents, 0);
  }

  protected async load(): Promise<void> {
    const params: Record<string, string> = { status: this.status };
    if (this.provider) {
      params['provider'] = this.provider;
    }
    try {
      this.payments.set(
        await firstValueFrom(this.http.get<PendingPayment[]>(`${this.api}/pending`, { params })),
      );
    } catch {
      this.payments.set([]);
    }
  }

  protected async confirm(p: PendingPayment): Promise<void> {
    await this.act(() =>
      firstValueFrom(this.http.post<PendingPayment>(`${this.api}/${p.paymentId}/confirmation`, {})),
    );
  }

  protected async reject(p: PendingPayment): Promise<void> {
    const reason = prompt(`Motif du rejet du paiement ${p.reference ?? ''}`);
    if (!reason) {
      return;
    }
    await this.act(() =>
      firstValueFrom(
        this.http.post<PendingPayment>(`${this.api}/${p.paymentId}/rejection`, { reason }),
      ),
    );
  }

  private async act(action: () => Promise<PendingPayment>): Promise<void> {
    this.message.set(null);
    try {
      await action();
      await this.load();
    } catch (error) {
      this.message.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Opération refusée',
      );
    }
  }
}
