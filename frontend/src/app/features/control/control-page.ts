import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  ApprovalView,
  ControlEvent,
  ControlFlag,
  ControlMetric,
  ControlOverview,
  DailyCountSummary,
  DailyCountVariance,
  EmployeeControl,
  NarcoticsRegister,
  ReceiptCheckView,
} from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import { IconName } from '../../shared/ui/icons';
import {
  METRICS,
  MOVEMENT_LABELS,
  MetricView,
  flaggedCount,
  isoDaysAgo,
  metricFoot,
} from './control-format';
import { DailyCountCard } from './daily-count-card';

type Tab = 'team' | 'daily' | 'narcotics' | 'receipts' | 'approvals';

const TABS: { id: Tab; label: string; icon: IconName }[] = [
  { id: 'team', label: 'Équipe', icon: 'customers' },
  { id: 'daily', label: 'Contrôle du jour', icon: 'counts' },
  { id: 'narcotics', label: 'Stupéfiants', icon: 'safe' },
  { id: 'receipts', label: 'Réceptions', icon: 'receive' },
  { id: 'approvals', label: 'Secondes validations', icon: 'key' },
];

const PRESETS = [
  { days: 6, label: '7 jours' },
  { days: 29, label: '30 jours' },
  { days: 89, label: '90 jours' },
];

const APPROVAL_LABELS: Record<ApprovalView['action'], string> = {
  STOCK_ADJUSTMENT: 'Correction de stock',
  STOCK_WRITE_OFF: 'Sortie de stock',
  STOCK_COUNT: 'Validation d’inventaire',
  SALE_RETURN: 'Retour client',
  SALE_CANCELLATION: 'Annulation de vente',
};

interface Selection {
  userId: string;
  metric: ControlMetric | 'AUDIT';
}

/**
 * "Contrôle interne": per employee over a period, the figures through which internal fraud
 * shows, each with an explained flag and the underlying events; the daily micro-count history
 * with the staff on duty; the narcotics register; receipts and second signatures.
 */
@Component({
  selector: 'app-control-page',
  imports: [
    DatePipe,
    DecimalPipe,
    RouterLink,
    MoneyPipe,
    HasPermissionDirective,
    Icon,
    DailyCountCard,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in space-y-5">
      <header class="page-header">
        <div>
          <h1 class="page-title">Contrôle interne</h1>
          <p class="page-subtitle">
            Du {{ from() | date: 'd MMM' }} au {{ to() | date: 'd MMM yyyy' }} · chaque écart
            rattaché à une période et aux personnes présentes
          </p>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <div class="segmented" role="tablist" aria-label="Période">
            @for (p of presets; track p.days) {
              <button
                type="button"
                role="tab"
                [attr.aria-selected]="days() === p.days"
                (click)="period(p.days)"
              >
                {{ p.label }}
              </button>
            }
          </div>
          <a class="btn btn-secondary" routerLink="/control/alerts">
            <app-icon name="bell" [size]="16" />
            Alertes
            @if ((overview()?.openAlerts ?? 0) > 0) {
              <span class="badge badge-danger tabular-nums">{{ overview()?.openAlerts }}</span>
            }
          </a>
          <a
            class="btn btn-ghost btn-icon"
            routerLink="/control/settings"
            aria-label="Réglages du contrôle interne"
            *appHasPermission="'CONTROL_SETTINGS'"
          >
            <app-icon name="settings" [size]="16" />
          </a>
        </div>
      </header>

      <div class="-mx-1 overflow-x-auto px-1">
        <div class="segmented" role="tablist" aria-label="Vues du contrôle interne">
          @for (t of tabs; track t.id) {
            <button
              type="button"
              role="tab"
              class="inline-flex items-center gap-1.5 whitespace-nowrap"
              [attr.aria-selected]="tab() === t.id"
              (click)="open(t.id)"
            >
              <app-icon [name]="t.icon" [size]="15" />{{ t.label }}
            </button>
          }
        </div>
      </div>

      @switch (tab()) {
        @case ('team') {
          @if (overview(); as o) {
            <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="customers" [size]="16" /></span>Personnes
                  actives
                </div>
                <div class="kpi-value">{{ activeCount() }}</div>
                <div class="kpi-foot">sur la période</div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon kpi-danger"><app-icon name="activity" [size]="16" /></span
                  >Signalées
                </div>
                <div class="kpi-value" [class.text-danger]="o.flaggedEmployees > 0">
                  {{ o.flaggedEmployees }}
                </div>
                <div class="kpi-foot">au moins un indicateur anormal</div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="ban" [size]="16" /></span>Annulations
                </div>
                <div class="kpi-value">{{ totalCancellations() }}</div>
                <div class="kpi-foot">dont {{ totalAfterPayment() }} après paiement</div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="stock" [size]="16" /></span>Sorties de
                  stock
                </div>
                <div class="kpi-value">{{ totalLosses() | money }}</div>
                <div class="kpi-foot">au coût, ajustements compris</div>
              </div>
            </div>

            <div class="space-y-4">
              @for (e of o.employees; track e.userId) {
                <article class="card" [attr.data-testid]="'employee-' + e.userId">
                  <div class="card-header flex-wrap gap-3">
                    <div class="flex min-w-0 items-center gap-3">
                      <span class="avatar">{{ initials(e.displayName) }}</span>
                      <div class="min-w-0">
                        <h2 class="truncate text-base font-semibold">{{ e.displayName }}</h2>
                        <p class="text-xs text-muted tabular-nums">
                          {{ e.sales }} vente(s) · {{ e.salesNetCents | money }}
                          @if (!e.active) {
                            · compte désactivé
                          }
                        </p>
                      </div>
                    </div>
                    @if (flags(e) > 0) {
                      <span class="badge badge-danger"
                        ><app-icon name="alert" [size]="13" />{{ flags(e) }} signalement(s)</span
                      >
                    } @else {
                      <span class="badge badge-success"
                        ><span class="dot"></span>Dans la norme</span
                      >
                    }
                  </div>
                  <div class="tiles">
                    @for (m of metrics; track m.metric) {
                      @let flag = flagOf(e, m.metric);
                      <button
                        type="button"
                        class="tile"
                        [class.tile-flagged]="flag?.flagged"
                        [class.tile-selected]="isSelected(e, m.metric)"
                        [attr.aria-pressed]="isSelected(e, m.metric)"
                        [attr.aria-label]="m.label + ' de ' + e.displayName"
                        (click)="select(e, m.metric)"
                      >
                        <span class="tile-label">
                          <app-icon [name]="m.icon" [size]="13" />{{ m.label }}
                        </span>
                        <span class="tile-value tabular-nums">
                          @switch (m.unit) {
                            @case ('money') {
                              {{ flag?.value ?? 0 | money }}
                            }
                            @case ('percent') {
                              {{ flag?.value ?? 0 | number: '1.0-1' }} %
                            }
                            @default {
                              {{ flag?.value ?? 0 }}
                            }
                          }
                        </span>
                        @if (foot(e, m.metric); as text) {
                          <span class="tile-foot">{{ text }}</span>
                        }
                        @if (flag?.flagged) {
                          <span class="tile-flag" aria-label="Signalé"
                            ><app-icon name="alert" [size]="12"
                          /></span>
                        }
                      </button>
                    }
                  </div>
                  @if (selection()?.userId === e.userId) {
                    <div class="border-t border-line p-4 sm:p-5">
                      @if (selectedFlag(e); as flag) {
                        <div
                          class="alert mb-4"
                          [class.alert-danger]="flag.flagged"
                          [class.alert-info]="!flag.flagged"
                        >
                          <app-icon [name]="flag.flagged ? 'alert' : 'info'" [size]="16" />
                          <div>
                            <p class="font-semibold">
                              {{
                                flag.flagged ? 'Pourquoi ce signalement' : 'Lecture de l’indicateur'
                              }}
                            </p>
                            <p>{{ flag.explanation }}</p>
                          </div>
                        </div>
                      }
                      <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
                        <h3 class="text-sm font-semibold">
                          {{ selection()?.metric === 'AUDIT' ? 'Journal d’audit' : 'Opérations' }}
                        </h3>
                        <div class="flex gap-2">
                          @if (selection()?.metric !== 'AUDIT') {
                            <button
                              type="button"
                              class="btn btn-ghost btn-sm"
                              (click)="select(e, 'AUDIT')"
                            >
                              <app-icon name="history" [size]="14" />Journal d’audit
                            </button>
                          }
                          <button
                            type="button"
                            class="btn btn-ghost btn-sm"
                            (click)="selection.set(null)"
                          >
                            Fermer
                          </button>
                        </div>
                      </div>
                      @if (events(); as list) {
                        @if (list.length === 0) {
                          <p class="py-4 text-sm text-subtle">Aucune opération sur la période.</p>
                        } @else {
                          <ul class="events">
                            @for (ev of list; track $index) {
                              <li class="event">
                                <span class="event-time tabular-nums">{{
                                  ev.at | date: 'dd/MM HH:mm'
                                }}</span>
                                <div class="min-w-0 flex-1">
                                  <p class="truncate text-sm font-medium">{{ ev.label }}</p>
                                  <p
                                    class="text-xs text-muted"
                                    [class.font-mono]="ev.type === 'AUDIT'"
                                    [class.break-all]="ev.type === 'AUDIT'"
                                  >
                                    {{ ev.detail }}
                                  </p>
                                </div>
                                @if (ev.amountCents !== null) {
                                  <span
                                    class="num text-sm font-semibold"
                                    [class.text-danger]="ev.amountCents < 0"
                                    >{{ ev.amountCents | money }}</span
                                  >
                                }
                              </li>
                            }
                          </ul>
                        }
                      } @else {
                        <div class="skeleton h-16"></div>
                      }
                    </div>
                  }
                </article>
              } @empty {
                <div class="card empty-state py-12">
                  <app-icon name="customers" [size]="22" />
                  <p>Aucune activité sur la période.</p>
                </div>
              }
            </div>
          } @else {
            <div class="skeleton h-64"></div>
          }
        }
        @case ('daily') {
          <app-daily-count-card />
          <section class="card">
            <div class="card-header">
              <h2 class="card-title">Historique du contrôle du jour</h2>
              <span class="badge">{{ daily().length }} jour(s)</span>
            </div>
            <div class="overflow-x-auto">
              <table class="data-table whitespace-nowrap">
                <thead>
                  <tr>
                    <th>Jour</th>
                    <th>Statut</th>
                    <th class="text-right">Produits</th>
                    <th class="text-right">Lots comptés</th>
                    <th class="text-right">Écart</th>
                    <th class="text-right">Valeur</th>
                    <th>Validé par</th>
                  </tr>
                </thead>
                <tbody>
                  @for (d of daily(); track d.id) {
                    <tr
                      class="cursor-pointer"
                      [class.row-alert]="d.sensitiveVariance"
                      [class.row-selected]="d.stockCountId && d.stockCountId === varianceCount()"
                      (click)="loadVariances(d)"
                    >
                      <td class="font-medium">{{ d.date | date: 'EEE d MMM' }}</td>
                      <td>
                        <span class="badge" [class]="dailyBadge(d)"
                          ><span class="dot"></span>{{ dailyLabel(d) }}</span
                        >
                      </td>
                      <td class="num">{{ d.products }}</td>
                      <td class="num">{{ d.countedLines }} / {{ d.lineCount }}</td>
                      <td class="num" [class.text-danger]="d.varianceUnits < 0">
                        {{ d.varianceUnits > 0 ? '+' : '' }}{{ d.varianceUnits }}
                      </td>
                      <td class="num" [class.text-danger]="d.varianceValueCents < 0">
                        {{ d.varianceValueCents | money }}
                      </td>
                      <td>{{ d.validatedBy ?? '—' }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="7">
                        <div class="empty-state">
                          <app-icon name="counts" [size]="22" />
                          <p>Aucun contrôle sur la période.</p>
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (variances(); as list) {
              <div class="border-t border-line p-4 sm:p-5">
                <h3 class="mb-3 text-sm font-semibold">Écarts constatés et personnes présentes</h3>
                @for (v of list; track v.productId) {
                  <div class="variance">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="font-medium">{{ v.designation }}</span>
                      @if (v.sensitive) {
                        <span class="badge badge-danger"
                          ><app-icon name="safe" [size]="12" />coffre / stupéfiant</span
                        >
                      }
                      <span class="badge badge-danger tabular-nums"
                        >{{ v.variance > 0 ? '+' : '' }}{{ v.variance }}</span
                      >
                      <span class="text-sm text-muted"
                        >attendu {{ v.expected }}, compté {{ v.counted }} ·
                        {{ v.valueCents | money }}</span
                      >
                    </div>
                    <p class="mt-1 text-xs text-muted">
                      Apparu entre le {{ v.windowFrom | date: 'd MMM HH:mm' }} et le
                      {{ v.windowTo | date: 'd MMM HH:mm' }}
                      ({{
                        v.sincePreviousCount
                          ? 'depuis le comptage précédent du produit'
                          : 'premier comptage du produit : 30 derniers jours'
                      }}). Présents :
                    </p>
                    <div class="mt-1.5 flex flex-wrap gap-1.5">
                      @for (s of v.staffOnDuty; track s.userId) {
                        <span class="badge"
                          ><app-icon name="user" [size]="12" />{{ s.displayName }}</span
                        >
                      } @empty {
                        <span class="text-xs text-subtle"
                          >personne n’a travaillé sur la période</span
                        >
                      }
                    </div>
                  </div>
                } @empty {
                  <p class="text-sm text-success">Aucun écart.</p>
                }
              </div>
            }
          </section>
        }
        @case ('narcotics') {
          @if (narcotics(); as register) {
            <div class="alert alert-info">
              <app-icon name="info" [size]="16" />
              <span>
                Pour chaque stupéfiant : chaque entrée et sortie avec le solde théorique, et chaque
                inventaire avec l’attendu, le compté et les deux signatures.
              </span>
            </div>
            @for (p of register.products; track p.productId) {
              <section class="card" [attr.data-testid]="'narcotic-' + p.cip13">
                <div class="card-header flex-wrap gap-3">
                  <div class="min-w-0">
                    <h2 class="card-title">{{ p.designation }}</h2>
                    <p class="text-xs text-subtle tabular-nums">
                      {{ p.cip13 }}
                      @if (p.locationCode) {
                        ·
                        <app-icon
                          [name]="p.locationKind === 'SAFE' ? 'safe' : 'mapPin'"
                          [size]="12"
                          class="align-[-2px]"
                        />
                        {{ p.locationCode }}
                      }
                    </p>
                  </div>
                  @if (p.gapAtLastCount) {
                    <span class="badge badge-danger"
                      ><app-icon name="alert" [size]="13" />Écart au dernier inventaire</span
                    >
                  } @else if (p.lastCount) {
                    <span class="badge badge-success"
                      ><span class="dot"></span>Conforme au dernier inventaire</span
                    >
                  } @else {
                    <span class="badge badge-warning"
                      ><span class="dot"></span>Jamais inventorié</span
                    >
                  }
                </div>
                <div class="grid grid-cols-2 gap-3 px-5 pb-4 sm:grid-cols-4">
                  <div class="figure">
                    <p class="figure-label">Solde théorique</p>
                    <p class="figure-value">{{ p.theoreticalBalance }}</p>
                  </div>
                  <div class="figure" [class.figure-alert]="p.onHand !== p.theoreticalBalance">
                    <p class="figure-label">En stock (lots)</p>
                    <p class="figure-value">{{ p.onHand }}</p>
                  </div>
                  <div class="figure" [class.figure-alert]="p.gapAtLastCount">
                    <p class="figure-label">Dernier compté</p>
                    @if (p.lastCount; as c) {
                      <p class="figure-value">
                        {{ c.counted }}
                        <span class="text-xs font-normal text-muted">/ {{ c.expected }}</span>
                      </p>
                      <p class="text-[0.7rem] text-subtle">
                        {{ c.validatedAt | date: 'd MMM HH:mm' }}
                      </p>
                    } @else {
                      <p class="figure-value">—</p>
                    }
                  </div>
                  <div class="figure">
                    <p class="figure-label">Mouvements depuis</p>
                    <p class="figure-value">
                      {{ p.unitsSinceLastCount > 0 ? '+' : '' }}{{ p.unitsSinceLastCount }}
                    </p>
                  </div>
                </div>
                <div class="overflow-x-auto border-t border-line">
                  <table class="data-table">
                    <thead class="whitespace-nowrap">
                      <tr>
                        <th>Date</th>
                        <th>Mouvement</th>
                        <th>Référence</th>
                        <th class="text-right">Quantité</th>
                        <th class="text-right">Solde</th>
                        <th>Par</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr class="text-subtle">
                        <td colspan="4">
                          Solde d’ouverture au {{ register.from | date: 'd MMM' }}
                        </td>
                        <td class="num font-semibold">{{ p.openingBalance }}</td>
                        <td></td>
                      </tr>
                      @for (row of p.entries; track $index) {
                        <tr
                          [class.row-count]="row.type === 'COUNT'"
                          [class.row-gap]="row.type === 'COUNT' && row.gap !== 0"
                        >
                          <td class="whitespace-nowrap tabular-nums">
                            {{ row.at | date: 'dd/MM HH:mm' }}
                          </td>
                          <td class="whitespace-nowrap font-medium">
                            {{ movementLabel(row.type) }}
                          </td>
                          <td class="min-w-48 text-sm">
                            @if (row.type === 'COUNT') {
                              <span>Attendu {{ row.expected }}, compté {{ row.counted }}</span>
                              @if (row.gap !== 0) {
                                <span class="badge badge-danger ml-1 tabular-nums"
                                  >écart {{ (row.gap ?? 0) > 0 ? '+' : '' }}{{ row.gap }}</span
                                >
                              }
                              <span class="block text-xs text-muted">
                                Compté par {{ row.countedBy ?? '—' }} · validé par
                                {{ row.validatedBy ?? '—' }}
                                @if (row.approvedBy) {
                                  · 2<sup>e</sup> signature {{ row.approvedBy }}
                                } @else {
                                  · <span class="text-danger">sans seconde signature</span>
                                }
                              </span>
                            } @else {
                              {{ row.reference ?? '—' }}
                            }
                          </td>
                          <td class="num" [class.text-danger]="row.quantity < 0">
                            {{ row.quantity > 0 ? '+' : '' }}{{ row.quantity }}
                          </td>
                          <td class="num font-semibold">{{ row.balance }}</td>
                          <td class="whitespace-nowrap text-sm">
                            {{ row.type === 'COUNT' ? '' : (row.recordedBy ?? '—') }}
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              </section>
            } @empty {
              <div class="card empty-state py-12">
                <app-icon name="safe" [size]="22" />
                <p>Aucun stupéfiant en stock.</p>
              </div>
            }
          } @else {
            <div class="skeleton h-64"></div>
          }
        }
        @case ('receipts') {
          <section class="card">
            <div class="card-header">
              <h2 class="card-title">Réceptions comparées aux commandes</h2>
              <span class="badge">{{ receipts().length }}</span>
            </div>
            <div class="overflow-x-auto">
              <table class="data-table whitespace-nowrap">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Commande</th>
                    <th>Fournisseur</th>
                    <th>Réceptionnée par</th>
                    <th>Contrôles</th>
                    <th class="text-right">Écart</th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of receipts(); track r.receiptId) {
                    <tr [class.row-alert]="r.separationWarning">
                      <td class="tabular-nums">{{ r.receivedAt | date: 'dd/MM HH:mm' }}</td>
                      <td class="font-mono text-xs">{{ r.orderNumber }}</td>
                      <td>{{ r.supplier }}</td>
                      <td>{{ r.receivedBy ?? '—' }}</td>
                      <td>
                        <div class="flex gap-1">
                          @if (r.blind) {
                            <span class="badge badge-info"
                              ><app-icon name="eyeOff" [size]="12" />à l’aveugle</span
                            >
                          }
                          @if (r.separationWarning) {
                            <span class="badge badge-warning">acheteur = réceptionnaire</span>
                          }
                        </div>
                      </td>
                      <td class="num" [class.text-danger]="r.discrepancyUnits !== 0">
                        @if (r.discrepancyLines === 0) {
                          <span class="text-success">conforme</span>
                        } @else {
                          {{ r.discrepancyUnits > 0 ? '+' : '' }}{{ r.discrepancyUnits }} ({{
                            r.discrepancyLines
                          }}
                          ligne(s))
                        }
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6">
                        <div class="empty-state">
                          <app-icon name="receive" [size]="22" />
                          <p>Aucune réception sur commande sur la période.</p>
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }
        @case ('approvals') {
          <section class="card">
            <div class="card-header">
              <h2 class="card-title">Secondes validations (quatre yeux)</h2>
              <span class="badge">{{ approvals().length }}</span>
            </div>
            <div class="overflow-x-auto">
              <table class="data-table">
                <thead class="whitespace-nowrap">
                  <tr>
                    <th>Date</th>
                    <th>Opération</th>
                    <th class="text-right">Montant</th>
                    <th>Demandée par</th>
                    <th>Validée par</th>
                    <th>Motif</th>
                  </tr>
                </thead>
                <tbody>
                  @for (a of approvals(); track a.id) {
                    <tr>
                      <td class="whitespace-nowrap tabular-nums">
                        {{ a.approvedAt | date: 'dd/MM HH:mm' }}
                      </td>
                      <td class="whitespace-nowrap">
                        {{ approvalLabel(a) }}
                        @if (a.narcotic) {
                          <span class="badge badge-danger ml-1">stupéfiant</span>
                        }
                      </td>
                      <td class="num whitespace-nowrap">{{ a.amountCents | money }}</td>
                      <td class="whitespace-nowrap">{{ a.requestedBy }}</td>
                      <td class="whitespace-nowrap font-medium">{{ a.approvedBy }}</td>
                      <td class="min-w-48 text-sm text-muted">{{ a.reason }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6">
                        <div class="empty-state">
                          <app-icon name="key" [size]="22" />
                          <p>Aucune seconde validation sur la période.</p>
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }
      }
    </div>
  `,
  styles: `
    .avatar {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2.4rem;
      height: 2.4rem;
      flex-shrink: 0;
      border-radius: 999px;
      background: var(--brand-soft);
      color: var(--brand-text);
      font-size: 0.8rem;
      font-weight: 700;
    }
    .kpi-danger {
      background: var(--danger-soft);
      color: var(--danger-strong);
    }
    .tiles {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem;
      padding: 0 1rem 1rem;
    }
    @media (min-width: 768px) {
      .tiles {
        grid-template-columns: repeat(5, minmax(0, 1fr));
        padding: 0 1.25rem 1.25rem;
      }
    }
    .tile {
      position: relative;
      display: flex;
      min-width: 0;
      flex-direction: column;
      gap: 0.15rem;
      padding: 0.65rem 0.75rem;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      background: var(--surface-2);
      text-align: left;
      transition:
        border-color 120ms ease,
        background-color 120ms ease;
    }
    .tile:hover {
      border-color: var(--border-strong);
    }
    .tile-flagged {
      border-color: color-mix(in srgb, var(--danger) 45%, transparent);
      background: var(--danger-soft);
    }
    .tile-selected {
      outline: 2px solid var(--brand-primary);
      outline-offset: 1px;
    }
    .tile-label {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      overflow: hidden;
      color: var(--text-muted);
      font-size: 0.72rem;
      font-weight: 500;
      white-space: nowrap;
      text-overflow: ellipsis;
    }
    .tile-value {
      font-size: 1.05rem;
      font-weight: 650;
      color: var(--text);
    }
    .tile-flagged .tile-value {
      color: var(--danger-strong);
    }
    .tile-foot {
      overflow: hidden;
      color: var(--text-subtle);
      font-size: 0.68rem;
      white-space: nowrap;
      text-overflow: ellipsis;
    }
    .tile-flag {
      position: absolute;
      top: 0.5rem;
      right: 0.5rem;
      color: var(--danger-strong);
    }
    .events {
      display: grid;
      gap: 0.25rem;
    }
    .event {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      padding: 0.55rem 0.65rem;
      border-radius: var(--radius-sm);
      background: var(--surface-2);
    }
    .event-time {
      flex-shrink: 0;
      width: 5.5rem;
      color: var(--text-muted);
      font-size: 0.78rem;
    }
    .variance + .variance {
      margin-top: 0.85rem;
      padding-top: 0.85rem;
      border-top: 1px solid var(--border);
    }
    .row-alert td {
      background: var(--danger-soft);
    }
    .row-selected td {
      background: var(--brand-soft);
    }
    .row-count td {
      background: var(--surface-2);
    }
    .row-gap td {
      background: var(--warning-soft);
    }
    .figure {
      padding: 0.65rem 0.75rem;
      border-radius: var(--radius);
      background: var(--surface-2);
    }
    .figure-alert {
      background: var(--danger-soft);
    }
    .figure-label {
      color: var(--text-muted);
      font-size: 0.72rem;
      font-weight: 500;
    }
    .figure-value {
      margin-top: 0.1rem;
      font-size: 1.1rem;
      font-weight: 650;
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class ControlPage {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/control`;

  protected readonly tabs = TABS;
  protected readonly presets = PRESETS;
  protected readonly metrics: MetricView[] = METRICS;
  protected readonly tab = signal<Tab>(
    (inject(ActivatedRoute).snapshot.queryParamMap.get('tab') as Tab | null) ?? 'team',
  );
  protected readonly days = signal(29);
  protected readonly from = computed(() => isoDaysAgo(this.days()));
  protected readonly to = computed(() => isoDaysAgo(0));

  protected readonly overview = signal<ControlOverview | null>(null);
  protected readonly selection = signal<Selection | null>(null);
  protected readonly events = signal<ControlEvent[] | null>(null);
  protected readonly daily = signal<DailyCountSummary[]>([]);
  protected readonly variances = signal<DailyCountVariance[] | null>(null);
  protected readonly varianceCount = signal<string | null>(null);
  protected readonly narcotics = signal<NarcoticsRegister | null>(null);
  protected readonly receipts = signal<ReceiptCheckView[]>([]);
  protected readonly approvals = signal<ApprovalView[]>([]);

  protected readonly activeCount = computed(
    () =>
      this.overview()?.employees.filter(
        (e) => e.sales > 0 || flaggedCount(e) > 0 || e.cashSessions > 0,
      ).length ?? 0,
  );
  protected readonly totalCancellations = computed(
    () => this.overview()?.employees.reduce((sum, e) => sum + e.cancellations, 0) ?? 0,
  );
  protected readonly totalAfterPayment = computed(
    () => this.overview()?.employees.reduce((sum, e) => sum + e.cancellationsAfterPayment, 0) ?? 0,
  );
  protected readonly totalLosses = computed(
    () =>
      this.overview()?.employees.reduce(
        (sum, e) => sum + e.writeOffsValueCents + Math.max(0, -e.adjustmentsValueCents),
        0,
      ) ?? 0,
  );

  constructor() {
    void this.load();
  }

  protected period(days: number): void {
    this.days.set(days);
    this.selection.set(null);
    this.variances.set(null);
    void this.load();
  }

  protected open(tab: Tab): void {
    this.tab.set(tab);
    void this.router.navigate([], { queryParams: { tab }, replaceUrl: true });
    void this.load();
  }

  protected initials(name: string): string {
    const words = name
      .replace(/^(Dr\.?|M\.|Mme)\s+/i, '')
      .split(/\s+/)
      .filter(Boolean);
    return ((words[0]?.[0] ?? '?') + (words[1]?.[0] ?? '')).toUpperCase();
  }

  protected flags(e: EmployeeControl): number {
    return flaggedCount(e);
  }

  protected flagOf(e: EmployeeControl, metric: ControlMetric): ControlFlag | undefined {
    return e.flags.find((f) => f.metric === metric);
  }

  protected foot(e: EmployeeControl, metric: ControlMetric): string | null {
    return metricFoot(e, metric);
  }

  protected isSelected(e: EmployeeControl, metric: ControlMetric): boolean {
    const s = this.selection();
    return s?.userId === e.userId && s.metric === metric;
  }

  protected selectedFlag(e: EmployeeControl): ControlFlag | undefined {
    const metric = this.selection()?.metric;
    return metric && metric !== 'AUDIT' ? this.flagOf(e, metric) : undefined;
  }

  protected movementLabel(type: string): string {
    return MOVEMENT_LABELS[type] ?? type;
  }

  protected approvalLabel(a: ApprovalView): string {
    return APPROVAL_LABELS[a.action];
  }

  protected dailyLabel(d: DailyCountSummary): string {
    return d.status === 'VALIDATED'
      ? 'Validé'
      : d.status === 'OPEN'
        ? 'En cours'
        : d.status === 'CANCELLED'
          ? 'Annulé'
          : 'Non fait';
  }

  protected dailyBadge(d: DailyCountSummary): string {
    if (d.status === 'VALIDATED') {
      return d.varianceUnits !== 0 ? 'badge-danger' : 'badge-success';
    }
    return d.status === 'OPEN' ? 'badge-info' : 'badge-warning';
  }

  protected async select(e: EmployeeControl, metric: ControlMetric | 'AUDIT'): Promise<void> {
    if (this.selection()?.userId === e.userId && this.selection()?.metric === metric) {
      this.selection.set(null);
      return;
    }
    this.selection.set({ userId: e.userId, metric });
    this.events.set(null);
    const params = { from: this.from(), to: this.to() };
    try {
      const url =
        metric === 'AUDIT'
          ? `${this.api}/employees/${e.userId}/audit`
          : `${this.api}/employees/${e.userId}/events`;
      this.events.set(
        await firstValueFrom(
          this.http.get<ControlEvent[]>(url, {
            params: metric === 'AUDIT' ? params : { ...params, metric },
          }),
        ),
      );
    } catch {
      this.events.set([]);
      this.toast.error('Opérations indisponibles');
    }
  }

  protected async loadVariances(d: DailyCountSummary): Promise<void> {
    if (!d.stockCountId || d.status !== 'VALIDATED') {
      return;
    }
    this.varianceCount.set(d.stockCountId);
    this.variances.set(null);
    try {
      this.variances.set(
        await firstValueFrom(
          this.http.get<DailyCountVariance[]>(
            `${this.api}/daily-counts/${d.stockCountId}/variances`,
          ),
        ),
      );
    } catch {
      this.variances.set([]);
    }
  }

  private async load(): Promise<void> {
    const params = { from: this.from(), to: this.to() };
    try {
      switch (this.tab()) {
        case 'team':
          this.overview.set(
            await firstValueFrom(
              this.http.get<ControlOverview>(`${this.api}/overview`, { params }),
            ),
          );
          break;
        case 'daily':
          this.daily.set(
            await firstValueFrom(
              this.http.get<DailyCountSummary[]>(`${this.api}/daily-counts`, { params }),
            ),
          );
          break;
        case 'narcotics':
          this.narcotics.set(
            await firstValueFrom(
              this.http.get<NarcoticsRegister>(`${this.api}/narcotics`, { params }),
            ),
          );
          break;
        case 'receipts':
          this.receipts.set(
            await firstValueFrom(
              this.http.get<ReceiptCheckView[]>(`${this.api}/receipt-checks`, { params }),
            ),
          );
          break;
        case 'approvals':
          this.approvals.set(
            await firstValueFrom(
              this.http.get<ApprovalView[]>(`${this.api}/approvals`, { params }),
            ),
          );
          break;
      }
    } catch {
      this.toast.error('Données du contrôle interne indisponibles');
    }
  }
}
