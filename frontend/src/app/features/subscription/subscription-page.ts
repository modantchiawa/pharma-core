import { DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { PharmacySubscription } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import {
  PAYMENT_METHOD_LABELS,
  ago,
  bannerFor,
  daysLabel,
  statusLabel,
  statusTone,
} from '../../core/subscription/license-text';
import { LicenseStateService } from '../../core/subscription/license-state.service';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';

/** The owner's view of the pharmacy's subscription, its payments and its offline license. */
@Component({
  selector: 'app-subscription-page',
  imports: [DatePipe, MoneyPipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Mon abonnement</h1>
          <p class="page-subtitle">
            Formule, période payée, licence de l'officine et règlements enregistrés par l'éditeur.
          </p>
        </div>
        <button type="button" class="btn btn-secondary" (click)="renew()" [disabled]="renewing()">
          <app-icon name="refresh" [size]="16" [class.animate-spin]="renewing()" />
          Vérifier la licence
        </button>
      </header>

      @if (data(); as d) {
        <div class="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2 sm:gap-4 xl:grid-cols-4">
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="license" [size]="16" /></span>
              Formule
            </div>
            <div class="kpi-value">{{ d.plan?.name ?? d.status.plan ?? '—' }}</div>
            <div class="kpi-foot">
              @if (d.plan; as plan) {
                {{ plan.monthlyPriceXof | money: 'XOF' }} par mois · jusqu'à
                {{ plan.maxTerminals }} postes
              }
            </div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="checkCircle" [size]="16" /></span>
              Statut
            </div>
            <div class="kpi-value">
              <span
                class="badge text-sm"
                [class.badge-success]="tone() === 'success'"
                [class.badge-warning]="tone() === 'warning'"
                [class.badge-danger]="tone() === 'danger'"
                data-testid="subscription-status"
                ><span class="dot"></span>{{ label() }}</span
              >
            </div>
            <div class="kpi-foot">
              {{ banner()?.title ?? 'Ventes et modifications autorisées' }}
            </div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="calendar" [size]="16" /></span>
              Payé jusqu'au
            </div>
            <div class="kpi-value">
              {{ d.status.paidUntil ? (d.status.paidUntil | date: 'd MMMM y') : 'Sans échéance' }}
            </div>
            <div class="kpi-foot">
              @if (paidDaysLeft() !== null) {
                @if (paidDaysLeft()! > 0) {
                  Encore {{ daysLabel(paidDaysLeft()) }}
                } @else if (d.status.graceUntil) {
                  Délai de grâce jusqu'au {{ d.status.graceUntil | date: 'd MMMM' }}
                }
              }
            </div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"
                ><app-icon [name]="d.status.mode === 'HUB' ? 'server' : 'globe'" [size]="16"
              /></span>
              Licence hors ligne
            </div>
            <div class="kpi-value text-base leading-snug">
              Vérifiée {{ ago(d.status.license.verifiedAt) }}
            </div>
            <div class="kpi-foot">
              @if (d.status.license.validUntil) {
                Valable jusqu'au {{ d.status.license.validUntil | date: 'd MMMM y, HH:mm' }}
              }
              @if (d.status.license.serial) {
                · n° {{ d.status.license.serial }}
              }
            </div>
          </div>
        </div>

        <div class="grid gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
          <section class="card overflow-hidden">
            <div class="card-header">
              <h2 class="card-title">Règlements</h2>
              <span class="badge">{{ d.payments.length }}</span>
            </div>
            <div class="overflow-x-auto">
              <table class="data-table [&_th]:whitespace-nowrap">
                <thead>
                  <tr>
                    <th>Reçu le</th>
                    <th class="num">Montant</th>
                    <th>Mode</th>
                    <th>Référence</th>
                    <th>Période couverte</th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of d.payments; track p.id) {
                    <tr>
                      <td class="whitespace-nowrap">{{ p.paidAt | date: 'dd/MM/yyyy' }}</td>
                      <td class="num font-semibold whitespace-nowrap">
                        {{ p.amountXof | money: 'XOF' }}
                      </td>
                      <td class="whitespace-nowrap">{{ methods[p.method] }}</td>
                      <td class="text-muted">{{ p.reference ?? '—' }}</td>
                      <td class="whitespace-nowrap text-muted">
                        {{ p.periodFrom | date: 'dd/MM/yy' }} → {{ p.periodTo | date: 'dd/MM/yy' }}
                        <span class="text-subtle">· {{ p.months }} mois</span>
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="5">
                        <div class="empty-state">
                          <app-icon name="receipt" [size]="22" />
                          <p>Aucun règlement enregistré pour le moment.</p>
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>

          <aside class="card">
            <div class="card-header">
              <h2 class="card-title">Renouveler</h2>
            </div>
            <div class="card-body space-y-3 text-sm">
              <p class="text-muted">
                Les règlements se font hors de l'application (mobile money, virement, espèces ou
                chèque). L'éditeur les enregistre : la licence est mise à jour aussitôt sur le cloud
                et à la prochaine synchronisation sur le serveur de l'officine.
              </p>
              @if (d.vendor.name) {
                <p class="font-semibold">{{ d.vendor.name }}</p>
              }
              @if (d.vendor.phone) {
                <a class="flex items-center gap-2 text-brand" [href]="'tel:' + d.vendor.phone">
                  <app-icon name="phone" [size]="16" />{{ d.vendor.phone }}
                </a>
              }
              @if (d.vendor.email) {
                <a class="flex items-center gap-2 text-brand" [href]="'mailto:' + d.vendor.email">
                  <app-icon name="mail" [size]="16" />{{ d.vendor.email }}
                </a>
              }
              <p class="hint">
                Le serveur de l'officine se bloque de lui-même s'il ne joint pas le cloud pendant 15
                jours : laissez-le connecté à Internet régulièrement.
              </p>
            </div>
          </aside>
        </div>
      } @else {
        <div class="card">
          <div class="empty-state">
            <app-icon name="license" [size]="22" />
            <p>{{ failed() ? 'Abonnement indisponible hors connexion.' : 'Chargement…' }}</p>
          </div>
        </div>
      }
    </div>
  `,
})
export class SubscriptionPage {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly license = inject(LicenseStateService);
  private readonly toasts = inject(ToastService);

  protected readonly data = signal<PharmacySubscription | null>(null);
  protected readonly failed = signal(false);
  protected readonly renewing = signal(false);
  protected readonly methods = PAYMENT_METHOD_LABELS;
  protected readonly daysLabel = daysLabel;
  protected readonly label = computed(() => statusLabel(this.license.state()));
  protected readonly tone = computed(() => statusTone(this.license.state()));
  protected readonly banner = computed(() => bannerFor(this.license.state()));
  protected readonly paidDaysLeft = computed(() => {
    const paidUntil = this.data()?.status.paidUntil;
    return paidUntil
      ? Math.max(0, Math.ceil((Date.parse(paidUntil) - Date.now()) / 86_400_000))
      : null;
  });

  constructor() {
    void this.load();
  }

  protected ago(iso: string | null): string {
    return ago(iso, Date.now());
  }

  protected async renew(): Promise<void> {
    this.renewing.set(true);
    try {
      await this.license.renew();
      await this.load();
      this.toasts.success('Licence vérifiée');
    } catch (error) {
      const detail = (error as HttpErrorResponse).error?.detail as string | undefined;
      this.toasts.error(detail ?? 'Licence non vérifiée : serveur injoignable');
    } finally {
      this.renewing.set(false);
    }
  }

  private async load(): Promise<void> {
    try {
      const subscription = await firstValueFrom(
        this.http.get<PharmacySubscription>(`${this.env.apiUrl}/v1/subscription`),
      );
      this.license.accept(subscription.status);
      this.data.set(subscription);
      this.failed.set(false);
    } catch {
      this.failed.set(true);
    }
  }
}
