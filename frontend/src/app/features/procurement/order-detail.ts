import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom, Observable } from 'rxjs';
import { PurchaseOrder, PurchaseOrderReceipt } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { BrandingService } from '../../core/branding/branding.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { PrintSettingsService } from '../../core/printing/print-settings.service';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { placeOf } from '../../shared/storage-location';
import { LocationChip } from '../../shared/ui/location-chip';
import { Icon } from '../../shared/ui/icon';
import { OrderReceive } from './order-receive';
import { PurchaseOrderPrint } from './purchase-order-print';
import {
  daysLate,
  errorDetail,
  isoToday,
  mailtoLink,
  orderStatus,
  orderTimeline,
  receivedPercent,
  whatsappLink,
} from './purchase-orders';

type Mode = 'view' | 'edit' | 'receive' | 'reschedule' | 'close' | 'cancel';

/**
 * Side panel of one purchase order (full screen on a phone): timeline, lines with what is
 * still awaited, supplier contact, and the actions its status allows — edit and send a draft,
 * send it by WhatsApp or e-mail, print it, receive a delivery, move the delivery date, close it.
 */
@Component({
  selector: 'app-order-detail',
  imports: [
    FormsModule,
    DatePipe,
    MoneyPipe,
    Icon,
    HasPermissionDirective,
    OrderReceive,
    PurchaseOrderPrint,
    LocationChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'escape()' },
  template: `
    <div
      class="fixed inset-0 z-40 bg-[rgb(2_6_12/0.45)] backdrop-blur-[2px] print:hidden"
      aria-hidden="true"
      (click)="closed.emit()"
    ></div>
    <aside
      class="fixed inset-y-0 right-0 z-50 flex w-full flex-col border-l border-line bg-surface shadow-[var(--shadow-lg)] sm:max-w-2xl print:hidden animate-in"
      role="dialog"
      aria-modal="true"
      data-side-panel
      [attr.aria-label]="'Commande ' + (order()?.number ?? '')"
    >
      @if (order(); as o) {
        <header class="flex items-start gap-3 border-b border-line px-4 py-3 sm:px-5">
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-2">
              <h2 class="font-mono text-base font-semibold">{{ o.number }}</h2>
              <span class="badge" [class]="status().badge"
                ><span class="dot"></span>{{ status().label }}</span
              >
              @if (o.overdue) {
                <span class="badge badge-danger">En retard de {{ late() }} j</span>
              }
            </div>
            <p class="mt-0.5 truncate text-sm text-muted">
              {{ o.supplierName }} · {{ o.totalCents | money }} HT
            </p>
          </div>
          <button
            type="button"
            class="btn btn-ghost btn-icon"
            aria-label="Fermer"
            (click)="closed.emit()"
          >
            <app-icon name="x" [size]="18" />
          </button>
        </header>

        <div class="flex-1 space-y-5 overflow-y-auto px-4 py-4 sm:px-5">
          @switch (mode()) {
            @case ('receive') {
              <section aria-label="Réception">
                <h3 class="mb-3 text-sm font-semibold">Réceptionner la livraison</h3>
                <app-order-receive
                  [order]="o"
                  (received)="afterReceipt()"
                  (cancelled)="mode.set('view')"
                />
              </section>
            }
            @default {
              <!-- actions according to the status -->
              @if (o.status === 'DRAFT' || open()) {
                <div
                  class="flex flex-wrap gap-2"
                  *appHasPermission="['PROCUREMENT_MANAGE', 'STOCK_RECEIVE']"
                >
                  @if (o.status === 'DRAFT') {
                    <ng-container *appHasPermission="'PROCUREMENT_MANAGE'">
                      @if (mode() === 'edit') {
                        <button
                          type="button"
                          class="btn btn-primary btn-sm"
                          [disabled]="busy()"
                          (click)="saveQuantities(o)"
                        >
                          <app-icon name="check" [size]="14" />Enregistrer
                        </button>
                        <button
                          type="button"
                          class="btn btn-ghost btn-sm"
                          (click)="startEdit(o, false)"
                        >
                          Annuler
                        </button>
                      } @else {
                        <button
                          type="button"
                          class="btn btn-primary btn-sm"
                          [disabled]="busy()"
                          (click)="send(o)"
                        >
                          <app-icon name="send" [size]="14" />Envoyer la commande
                        </button>
                        <button
                          type="button"
                          class="btn btn-secondary btn-sm"
                          (click)="startEdit(o, true)"
                        >
                          <app-icon name="pencil" [size]="14" />Modifier les quantités
                        </button>
                        <button
                          type="button"
                          class="btn btn-ghost btn-sm text-danger"
                          (click)="mode.set('cancel')"
                        >
                          <app-icon name="trash" [size]="14" />Annuler
                        </button>
                      }
                    </ng-container>
                  }
                  @if (open()) {
                    <button
                      type="button"
                      class="btn btn-primary btn-sm"
                      (click)="mode.set('receive')"
                    >
                      <app-icon name="receive" [size]="14" />Réceptionner
                    </button>
                    <ng-container *appHasPermission="'PROCUREMENT_MANAGE'">
                      <button
                        type="button"
                        class="btn btn-secondary btn-sm"
                        (click)="startReschedule(o)"
                      >
                        <app-icon name="calendar" [size]="14" />Changer la date prévue
                      </button>
                      @if (o.receivedUnits > 0) {
                        <button
                          type="button"
                          class="btn btn-secondary btn-sm"
                          (click)="mode.set('close')"
                        >
                          <app-icon name="checkCircle" [size]="14" />Solder
                        </button>
                      } @else {
                        <button
                          type="button"
                          class="btn btn-ghost btn-sm text-danger"
                          (click)="mode.set('cancel')"
                        >
                          <app-icon name="ban" [size]="14" />Annuler
                        </button>
                      }
                    </ng-container>
                  }
                </div>
              }

              @if (o.status === 'DRAFT' && belowMinimum()) {
                <div class="alert alert-warning">
                  <app-icon name="alert" [size]="16" class="mt-0.5" />
                  <span>
                    Montant sous le minimum de commande du fournisseur ({{
                      o.supplier.minOrderCents | money
                    }}) : l'envoi sera refusé.
                  </span>
                </div>
              }

              @if (mode() === 'reschedule') {
                <form class="card card-body space-y-3" (ngSubmit)="reschedule(o)">
                  <label class="block">
                    <span class="label">Nouvelle date de livraison annoncée</span>
                    <input
                      class="input sm:w-56"
                      type="date"
                      name="expectedAt"
                      [min]="today()"
                      [(ngModel)]="newDate"
                      required
                    />
                  </label>
                  <div class="flex gap-2">
                    <button
                      type="submit"
                      class="btn btn-primary btn-sm"
                      [disabled]="!newDate || busy()"
                    >
                      Enregistrer
                    </button>
                    <button type="button" class="btn btn-ghost btn-sm" (click)="mode.set('view')">
                      Annuler
                    </button>
                  </div>
                </form>
              }
              @if (mode() === 'close') {
                <form class="card card-body space-y-3" (ngSubmit)="closeShort(o)">
                  <p class="text-sm">
                    <strong>{{ o.orderedUnits - o.receivedUnits }} unité(s)</strong> ne seront pas
                    livrées. La commande passe en « Soldée » et ne compte plus dans les quantités en
                    commande.
                  </p>
                  <label class="block">
                    <span class="label">Motif <span class="text-subtle">(facultatif)</span></span>
                    <input
                      class="input"
                      name="reason"
                      maxlength="255"
                      [(ngModel)]="reason"
                      placeholder="Rupture chez le grossiste"
                    />
                  </label>
                  <div class="flex gap-2">
                    <button type="submit" class="btn btn-primary btn-sm" [disabled]="busy()">
                      Solder la commande
                    </button>
                    <button type="button" class="btn btn-ghost btn-sm" (click)="mode.set('view')">
                      Retour
                    </button>
                  </div>
                </form>
              }
              @if (mode() === 'cancel') {
                <div class="card card-body space-y-3">
                  <p class="text-sm">
                    Annuler la commande {{ o.number }} ? Elle restera visible dans les commandes
                    clôturées.
                  </p>
                  <div class="flex gap-2">
                    <button
                      type="button"
                      class="btn btn-danger btn-sm"
                      [disabled]="busy()"
                      (click)="cancel(o)"
                    >
                      Annuler la commande
                    </button>
                    <button type="button" class="btn btn-ghost btn-sm" (click)="mode.set('view')">
                      Retour
                    </button>
                  </div>
                </div>
              }

              <!-- sending: as soon as the order has left, until it is closed -->
              @if (open()) {
                <section class="space-y-2" aria-label="Transmettre au fournisseur">
                  <h3 class="text-xs font-semibold uppercase tracking-wide text-subtle">
                    Transmettre au fournisseur
                  </h3>
                  <div class="grid grid-cols-2 gap-2 sm:grid-cols-4">
                    @if (whatsapp(); as link) {
                      <a class="btn btn-soft btn-sm" [href]="link" target="_blank" rel="noopener">
                        <app-icon name="chat" [size]="14" />WhatsApp
                      </a>
                    } @else {
                      <button
                        type="button"
                        class="btn btn-soft btn-sm"
                        disabled
                        title="Aucun numéro WhatsApp"
                      >
                        <app-icon name="chat" [size]="14" />WhatsApp
                      </button>
                    }
                    @if (mail(); as link) {
                      <a class="btn btn-soft btn-sm" [href]="link">
                        <app-icon name="mail" [size]="14" />E-mail
                      </a>
                    } @else {
                      <button
                        type="button"
                        class="btn btn-soft btn-sm"
                        disabled
                        title="Aucune adresse e-mail"
                      >
                        <app-icon name="mail" [size]="14" />E-mail
                      </button>
                    }
                    <button type="button" class="btn btn-secondary btn-sm" (click)="copyText()">
                      <app-icon name="copy" [size]="14" />Copier le texte
                    </button>
                    <button type="button" class="btn btn-secondary btn-sm" (click)="print()">
                      <app-icon name="printer" [size]="14" />Imprimer
                    </button>
                  </div>
                </section>
              }

              @if (o.notes) {
                <p
                  class="flex items-start gap-2 rounded-[var(--radius)] bg-surface-2 px-3 py-2 text-sm"
                >
                  <app-icon name="info" [size]="15" class="mt-0.5 text-muted" />{{ o.notes }}
                </p>
              }

              <!-- timeline -->
              <section aria-label="Historique">
                <h3 class="mb-2 text-xs font-semibold uppercase tracking-wide text-subtle">
                  Suivi
                </h3>
                <ol class="timeline">
                  @for (step of timeline(); track $index) {
                    <li [attr.data-state]="step.state">
                      <span class="timeline-dot" aria-hidden="true"></span>
                      <div class="min-w-0">
                        <p class="text-sm font-medium">{{ step.label }}</p>
                        <p class="text-xs text-muted">
                          @if (step.at) {
                            {{
                              step.dateOnly
                                ? (step.at | date: 'EEEE d MMMM')
                                : (step.at | date: 'd MMM, HH:mm')
                            }}
                          } @else {
                            En attente
                          }
                          @if (step.detail) {
                            ·
                            <span [class.text-danger]="step.state === 'late'">{{
                              step.detail
                            }}</span>
                          }
                        </p>
                      </div>
                    </li>
                  }
                </ol>
              </section>

              <!-- lines -->
              <section aria-label="Lignes">
                <div class="mb-2 flex items-center justify-between gap-2">
                  <h3 class="text-xs font-semibold uppercase tracking-wide text-subtle">
                    {{ o.lines.length }} référence(s)
                  </h3>
                  @if (o.status !== 'DRAFT') {
                    <span class="text-xs text-muted"
                      >{{ o.receivedUnits }} / {{ o.orderedUnits }} unités reçues</span
                    >
                  }
                </div>
                <ul class="divide-y divide-line rounded-[var(--radius)] border border-line">
                  @for (line of o.lines; track line.productId) {
                    <li class="flex flex-wrap items-center gap-x-3 gap-y-1.5 px-3 py-2.5">
                      <div class="min-w-0 flex-1 basis-48">
                        <p class="truncate text-sm font-medium">{{ line.designation }}</p>
                        <p class="text-xs text-subtle tabular-nums">
                          {{ line.cip13 }} · {{ line.unitCostCents | money }} HT
                        </p>
                        @if (placeOf(line); as place) {
                          <app-location-chip [place]="place" class="mt-1" />
                        }
                      </div>
                      @if (mode() === 'edit') {
                        <input
                          type="number"
                          min="0"
                          [step]="line.packSize"
                          class="input num min-h-8 w-24"
                          [attr.aria-label]="'Quantité, ' + line.designation"
                          [ngModel]="edits()[line.productId]"
                          (ngModelChange)="setEdit(line.productId, $event)"
                        />
                      } @else if (o.status === 'DRAFT') {
                        <span class="num text-sm font-medium">{{ line.quantityOrdered }}</span>
                      } @else {
                        <div class="w-full sm:w-44">
                          <div class="flex justify-between text-xs tabular-nums">
                            <span>{{ line.quantityReceived }} / {{ line.quantityOrdered }}</span>
                            @if (line.quantityOrdered > line.quantityReceived) {
                              <span [class.text-warning]="!open()" [class.text-muted]="open()">
                                {{ open() ? 'reste' : 'manque' }}
                                {{ line.quantityOrdered - line.quantityReceived }}
                              </span>
                            } @else {
                              <span class="text-success">complet</span>
                            }
                          </div>
                          <div
                            class="progress mt-1"
                            role="progressbar"
                            aria-valuemin="0"
                            aria-valuemax="100"
                            [attr.aria-valuenow]="
                              percent(line.quantityOrdered, line.quantityReceived)
                            "
                            [attr.aria-label]="'Reçu, ' + line.designation"
                          >
                            <span
                              [class.complete]="line.quantityReceived >= line.quantityOrdered"
                              [style.width.%]="percent(line.quantityOrdered, line.quantityReceived)"
                            ></span>
                          </div>
                        </div>
                      }
                    </li>
                  }
                </ul>
              </section>

              @if (receipts().length > 0) {
                <section aria-label="Réceptions">
                  <h3 class="mb-2 text-xs font-semibold uppercase tracking-wide text-subtle">
                    Réceptions
                  </h3>
                  <ul class="space-y-2">
                    @for (r of receipts(); track r.id) {
                      <li
                        class="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius)] border border-line px-3 py-2 text-sm"
                      >
                        <span>
                          {{ r.receivedAt | date: 'd MMM, HH:mm' }}
                          @if (r.supplierInvoiceNumber) {
                            · <span class="font-mono text-xs">{{ r.supplierInvoiceNumber }}</span>
                          }
                          <span class="text-muted">
                            · {{ r.lines }} ligne(s) · {{ r.receivedBy ?? '—' }}</span
                          >
                        </span>
                        <span class="num font-medium">
                          {{ r.totalExclVatCents | money }} HT
                          @if (r.vatCents > 0) {
                            <span class="text-xs text-muted">+ TVA {{ r.vatCents | money }}</span>
                          }
                        </span>
                      </li>
                    }
                  </ul>
                </section>
              }

              <!-- supplier -->
              <section
                class="rounded-[var(--radius)] bg-surface-2 p-3 text-sm"
                aria-label="Fournisseur"
              >
                <h3 class="mb-1 text-xs font-semibold uppercase tracking-wide text-subtle">
                  Fournisseur
                </h3>
                <p class="font-medium">{{ o.supplierName }}</p>
                @if (o.supplier.contactName) {
                  <p class="text-muted">{{ o.supplier.contactName }}</p>
                }
                <div class="mt-1.5 flex flex-wrap gap-x-4 gap-y-1">
                  @if (o.supplier.phone) {
                    <a
                      class="inline-flex items-center gap-1.5 text-brand"
                      [href]="'tel:' + o.supplier.phone"
                    >
                      <app-icon name="phone" [size]="13" />{{ o.supplier.phone }}
                    </a>
                  }
                  @if (o.supplier.whatsapp) {
                    <span class="inline-flex items-center gap-1.5"
                      ><app-icon name="chat" [size]="13" />{{ o.supplier.whatsapp }}</span
                    >
                  }
                  @if (o.supplier.email) {
                    <span class="inline-flex items-center gap-1.5"
                      ><app-icon name="mail" [size]="13" />{{ o.supplier.email }}</span
                    >
                  }
                </div>
                <p class="mt-1.5 text-xs text-muted">
                  Délai habituel {{ o.supplier.leadTimeDays }} j · minimum
                  {{ o.supplier.minOrderCents | money }}
                </p>
              </section>
            }
          }
        </div>
      } @else {
        <div class="space-y-3 p-5" aria-hidden="true">
          <div class="skeleton h-6 w-48"></div>
          <div class="skeleton h-4 w-64"></div>
          <div class="skeleton h-40 w-full"></div>
        </div>
      }
    </aside>
    @if (order(); as o) {
      <div class="hidden print:block"><app-purchase-order-print [order]="o" /></div>
    }
  `,
  styles: `
    .timeline {
      position: relative;
      display: grid;
      gap: 0.85rem;
    }
    .timeline > li {
      position: relative;
      display: flex;
      gap: 0.75rem;
      padding-left: 0.1rem;
    }
    .timeline > li:not(:last-child)::before {
      content: '';
      position: absolute;
      left: calc(0.1rem + 5px);
      top: 1.1rem;
      bottom: -0.85rem;
      width: 2px;
      background: var(--border);
    }
    .timeline-dot {
      flex: none;
      width: 12px;
      height: 12px;
      margin-top: 0.3rem;
      border-radius: 999px;
      background: var(--brand-primary);
      box-shadow: 0 0 0 3px var(--brand-soft);
    }
    [data-state='upcoming'] .timeline-dot {
      background: var(--surface);
      border: 2px solid var(--border-strong, var(--border));
      box-shadow: none;
    }
    [data-state='late'] .timeline-dot,
    [data-state='cancelled'] .timeline-dot {
      background: var(--danger);
      box-shadow: 0 0 0 3px var(--danger-soft);
    }
    .progress {
      height: 6px;
      border-radius: 999px;
      background: var(--surface-3);
      overflow: hidden;
    }
    .progress > span {
      display: block;
      height: 100%;
      border-radius: inherit;
      background: var(--brand-primary);
    }
    .progress > span.complete {
      background: var(--success);
    }
  `,
})
export class OrderDetail {
  protected readonly placeOf = placeOf;
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/procurement`;
  private readonly toast = inject(ToastService);
  private readonly branding = inject(BrandingService);
  private readonly printing = inject(PrintSettingsService);

  readonly orderId = input.required<string>();
  /** Today in the pharmacy calendar, from the tracking summary. */
  readonly today = input<string>(isoToday());
  readonly closed = output<void>();
  /** The order changed (sent, received, closed...): lists and figures must be refreshed. */
  readonly changed = output<PurchaseOrder>();

  protected readonly order = signal<PurchaseOrder | null>(null);
  protected readonly receipts = signal<PurchaseOrderReceipt[]>([]);
  protected readonly text = signal<string>('');
  protected readonly mode = signal<Mode>('view');
  protected readonly busy = signal(false);
  protected readonly edits = signal<Record<string, number>>({});
  protected newDate = '';
  protected reason = '';

  protected readonly status = computed(() => {
    const o = this.order();
    return o ? orderStatus(o) : { label: '', badge: '' };
  });
  protected readonly open = computed(() => {
    const status = this.order()?.status;
    return status === 'SENT' || status === 'PARTIALLY_RECEIVED';
  });
  protected readonly late = computed(() =>
    Math.max(daysLate(this.order()?.expectedAt ?? null, this.today()), 1),
  );
  protected readonly belowMinimum = computed(() => {
    const o = this.order();
    return !!o && o.totalCents < o.supplier.minOrderCents;
  });
  protected readonly timeline = computed(() => {
    const o = this.order();
    return o ? orderTimeline(o, this.receipts(), this.today()) : [];
  });
  protected readonly whatsapp = computed(() => {
    const o = this.order();
    return o ? whatsappLink(o.supplier.whatsapp ?? o.supplier.phone, this.text()) : null;
  });
  protected readonly mail = computed(() => {
    const o = this.order();
    if (!o) {
      return null;
    }
    const pharmacy = this.branding.branding().displayName;
    return mailtoLink(
      o.supplier.email,
      `Commande ${o.number} - ${pharmacy}`,
      `Bonjour,\n\nVeuillez trouver ci-dessous notre commande.\n\n${this.text()}\n\nCordialement,\n${pharmacy}`,
    );
  });

  constructor() {
    effect(() => {
      const id = this.orderId();
      this.mode.set('view');
      void this.load(id);
    });
  }

  protected escape(): void {
    if (this.mode() !== 'view') {
      this.mode.set('view');
    } else {
      this.closed.emit();
    }
  }

  protected percent(ordered: number, received: number): number {
    return receivedPercent(ordered, received);
  }

  protected startEdit(order: PurchaseOrder, editing: boolean): void {
    this.edits.set(Object.fromEntries(order.lines.map((l) => [l.productId, l.quantityOrdered])));
    this.mode.set(editing ? 'edit' : 'view');
  }

  protected setEdit(productId: string, value: number): void {
    this.edits.update((e) => ({ ...e, [productId]: Math.max(Number(value) || 0, 0) }));
  }

  protected startReschedule(order: PurchaseOrder): void {
    this.newDate =
      order.expectedAt && order.expectedAt >= this.today() ? order.expectedAt : this.today();
    this.mode.set('reschedule');
  }

  protected async saveQuantities(order: PurchaseOrder): Promise<void> {
    const lines = order.lines
      .filter((l) => (this.edits()[l.productId] ?? 0) > 0)
      .map((l) => ({
        productId: l.productId,
        quantity: this.edits()[l.productId],
        unitCostCents: l.unitCostCents,
        suggestedQuantity: l.suggestedQuantity,
        reason: l.reason,
      }));
    if (lines.length === 0) {
      this.toast.error('Gardez au moins une ligne, ou annulez la commande');
      return;
    }
    await this.act(
      () =>
        this.http.put<PurchaseOrder>(`${this.api}/orders/${order.id}`, {
          supplierId: order.supplierId,
          lines,
          notes: order.notes,
        }),
      'Quantités enregistrées',
    );
  }

  protected send(order: PurchaseOrder): Promise<void> {
    return this.act(
      () => this.http.post<PurchaseOrder>(`${this.api}/orders/${order.id}/send`, {}),
      `Commande ${order.number} envoyée : transmettez-la au fournisseur`,
    );
  }

  protected cancel(order: PurchaseOrder): Promise<void> {
    return this.act(
      () => this.http.post<PurchaseOrder>(`${this.api}/orders/${order.id}/cancellation`, {}),
      `Commande ${order.number} annulée`,
    );
  }

  protected reschedule(order: PurchaseOrder): Promise<void> {
    return this.act(
      () =>
        this.http.put<PurchaseOrder>(`${this.api}/orders/${order.id}/expected-date`, {
          expectedAt: this.newDate,
        }),
      'Date de livraison mise à jour',
    );
  }

  protected closeShort(order: PurchaseOrder): Promise<void> {
    return this.act(
      () =>
        this.http.post<PurchaseOrder>(`${this.api}/orders/${order.id}/closure`, {
          reason: this.reason.trim() || null,
        }),
      `Commande ${order.number} soldée`,
    );
  }

  protected async afterReceipt(): Promise<void> {
    this.mode.set('view');
    const order = await this.load(this.orderId());
    if (order) {
      this.changed.emit(order);
    }
  }

  protected async copyText(): Promise<void> {
    const text = this.text();
    try {
      await navigator.clipboard.writeText(text);
      this.toast.success('Texte copié : collez-le dans WhatsApp ou un e-mail');
    } catch {
      window.prompt('Copiez le texte de la commande', text);
    }
  }

  protected print(): void {
    this.printing.printPage('A4');
  }

  private async act(call: () => Observable<PurchaseOrder>, success: string): Promise<void> {
    this.busy.set(true);
    try {
      const updated = await firstValueFrom(call());
      this.order.set(updated);
      this.mode.set('view');
      this.toast.success(success);
      await this.loadExtras(updated);
      this.changed.emit(updated);
    } catch (error) {
      this.toast.error(errorDetail(error, 'Action refusée'));
    } finally {
      this.busy.set(false);
    }
  }

  private async load(id: string): Promise<PurchaseOrder | null> {
    try {
      const order = await firstValueFrom(this.http.get<PurchaseOrder>(`${this.api}/orders/${id}`));
      if (this.orderId() !== id) {
        return null;
      }
      this.order.set(order);
      await this.loadExtras(order);
      return order;
    } catch (error) {
      this.toast.error(errorDetail(error, 'Commande introuvable'));
      this.closed.emit();
      return null;
    }
  }

  private async loadExtras(order: PurchaseOrder): Promise<void> {
    const [receipts, text] = await Promise.all([
      order.status === 'DRAFT'
        ? Promise.resolve([])
        : firstValueFrom(
            this.http.get<PurchaseOrderReceipt[]>(`${this.api}/orders/${order.id}/receipts`),
          ),
      order.status === 'DRAFT'
        ? Promise.resolve('')
        : firstValueFrom(
            this.http.get(`${this.api}/orders/${order.id}/text`, { responseType: 'text' }),
          ),
    ]);
    this.receipts.set(receipts);
    this.text.set(text);
  }
}
