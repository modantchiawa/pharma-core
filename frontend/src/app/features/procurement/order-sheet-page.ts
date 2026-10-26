import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ProposalLine, ProposalSheet, PurchaseOrder, Supplier } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';

const REASON_LABELS: Record<string, string> = {
  BELOW_TARGET: 'sous la couverture',
  BELOW_THRESHOLD: 'sous le seuil',
  EXPIRING_REPLACEMENT: 'péremption proche',
  LOST_SALES: 'ventes perdues',
};

const REASON_BADGE: Record<string, string> = {
  BELOW_THRESHOLD: 'badge-warning',
  EXPIRING_REPLACEMENT: 'badge-info',
  LOST_SALES: 'badge-danger',
};

const ORDER_STATUS: Record<PurchaseOrder['status'], { label: string; badge: string }> = {
  DRAFT: { label: 'Brouillon', badge: '' },
  SENT: { label: 'Envoyée', badge: 'badge-info' },
  PARTIALLY_RECEIVED: { label: 'Reçue en partie', badge: 'badge-warning' },
  RECEIVED: { label: 'Reçue', badge: 'badge-success' },
  CANCELLED: { label: 'Annulée', badge: 'badge-danger' },
};

/**
 * Tomorrow's order sheet: the system proposes, the pharmacist adjusts quantities, then the
 * order is sent (text for WhatsApp/e-mail, or printed) and later received lot by lot.
 */
@Component({
  selector: 'app-order-sheet-page',
  imports: [FormsModule, MoneyPipe, DatePipe, DecimalPipe, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Commandes fournisseurs</h1>
          <p class="page-subtitle">
            Le système propose, vous ajustez les quantités, puis la commande part au fournisseur
          </p>
        </div>
        @if (tab() === 'proposal') {
          <button
            type="button"
            class="btn btn-primary"
            *appHasPermission="'PROCUREMENT_MANAGE'"
            [disabled]="!supplierId || selectedCount() === 0"
            (click)="createOrder()"
          >
            <app-icon name="plus" [size]="16" />
            Créer la commande ({{ selectedCount() }} lignes)
          </button>
        }
      </header>

      <div class="space-y-5">
        <div class="segmented" role="tablist">
          <button
            type="button"
            role="tab"
            class="inline-flex items-center gap-1.5 whitespace-nowrap"
            [attr.aria-selected]="tab() === 'proposal'"
            (click)="tab.set('proposal')"
          >
            <app-icon name="spreadsheet" [size]="15" />
            Fiche de commande
          </button>
          <button
            type="button"
            role="tab"
            class="inline-flex items-center gap-1.5 whitespace-nowrap"
            [attr.aria-selected]="tab() === 'orders'"
            (click)="openOrders()"
          >
            <app-icon name="procurement" [size]="15" />
            Commandes
          </button>
        </div>

        @if (status(); as text) {
          <div class="alert alert-info" role="status">
            <app-icon name="info" [size]="16" class="mt-0.5" />
            <span>{{ text }}</span>
          </div>
        }

        @if (tab() === 'proposal') {
          @if (sheet(); as sheet) {
            <div class="grid grid-cols-2 gap-4 xl:grid-cols-4">
              <div class="card kpi col-span-2 sm:col-span-1">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="banknote" [size]="16" /></span>
                  Estimation
                </div>
                <div class="kpi-value">{{ sheet.estimatedTotalCents | money }}</div>
                <div class="kpi-foot">Au prix d'achat des lignes proposées</div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="spreadsheet" [size]="16" /></span>
                  Lignes proposées
                </div>
                <div class="kpi-value">{{ sheet.lines.length }}</div>
                <div class="kpi-foot">{{ selectedCount() }} sélectionnée(s)</div>
              </div>
              <div class="card kpi col-span-2 max-sm:order-last sm:col-span-1">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="calendar" [size]="16" /></span>
                  Livraison visée
                </div>
                <div class="kpi-value">{{ sheet.date | date: 'dd/MM/yyyy' }}</div>
                <div class="kpi-foot">Fiche du jour pour demain</div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="activity" [size]="16" /></span>
                  Vitesse de vente
                </div>
                <div class="kpi-value">{{ sheet.periodDays }} jours</div>
                <div class="kpi-foot">Période d'observation des ventes</div>
              </div>
            </div>
          }

          <section class="card">
            <div class="card-header flex-wrap">
              <div class="flex min-w-0 flex-1 flex-wrap items-center gap-2">
                <select
                  class="select w-full sm:w-64"
                  aria-label="Fournisseur"
                  [(ngModel)]="supplierId"
                  (ngModelChange)="loadProposal()"
                >
                  <option [ngValue]="null">Tous les fournisseurs</option>
                  @for (s of suppliers(); track s.id) {
                    <option [ngValue]="s.id">{{ s.details.name }}</option>
                  }
                </select>
                <button type="button" class="btn btn-secondary" (click)="loadProposal()">
                  <app-icon name="refresh" [size]="16" />
                  Recalculer
                </button>
              </div>
            </div>
            @if (!supplierId) {
              <div class="border-b border-line p-4">
                <div class="alert alert-warning">
                  <app-icon name="alert" [size]="16" class="mt-0.5" />
                  <span
                    >Choisissez un fournisseur pour transformer la proposition en commande.</span
                  >
                </div>
              </div>
            }
            <div class="overflow-x-auto">
              <table class="data-table">
                <thead class="whitespace-nowrap">
                  <tr>
                    <th class="w-10"><span class="sr-only">Sélection</span></th>
                    <th>Produit</th>
                    <th>Fournisseur</th>
                    <th class="num text-right">Dispo</th>
                    <th class="num text-right">En cmd</th>
                    <th class="num text-right">Vente/j</th>
                    <th class="num text-right">Cible</th>
                    <th class="num text-right">Proposé</th>
                    <th class="num text-right">À commander</th>
                    <th>Motif</th>
                  </tr>
                </thead>
                <tbody>
                  @for (line of sheet()?.lines ?? []; track line.productId) {
                    <tr>
                      <td>
                        <input
                          type="checkbox"
                          [attr.aria-label]="'Commander ' + line.designation"
                          [checked]="quantities()[line.productId] > 0"
                          (change)="toggle(line, $event)"
                        />
                      </td>
                      <td class="min-w-56">
                        <p class="font-medium">{{ line.designation }}</p>
                        <p class="text-xs text-subtle tabular-nums">{{ line.cip13 }}</p>
                      </td>
                      <td class="whitespace-nowrap text-muted">{{ line.supplierName ?? '—' }}</td>
                      <td class="num whitespace-nowrap">
                        {{ line.available }}
                        @if (line.expiringSoon > 0) {
                          <span class="badge badge-warning ml-1"
                            >-{{ line.expiringSoon }} périm.</span
                          >
                        }
                      </td>
                      <td class="num">{{ line.onOrder }}</td>
                      <td class="num">{{ line.dailyDemand | number: '1.1-1' }}</td>
                      <td class="num">{{ line.targetStock }}</td>
                      <td class="num whitespace-nowrap">
                        {{ line.suggestedQuantity }}
                        <span class="text-xs text-subtle">×{{ line.packSize }}</span>
                      </td>
                      <td class="num">
                        <input
                          type="number"
                          min="0"
                          [step]="line.packSize"
                          class="input num ml-auto min-h-8 w-20"
                          [attr.aria-label]="'Quantité à commander, ' + line.designation"
                          [ngModel]="quantities()[line.productId]"
                          (ngModelChange)="setQuantity(line, $event)"
                        />
                      </td>
                      <td>
                        <div class="flex flex-wrap gap-1">
                          @for (r of line.reasons; track r) {
                            <span class="badge" [class]="reasonBadge(r)">{{ reasonLabel(r) }}</span>
                          }
                        </div>
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="10">
                        <div class="empty-state">
                          <app-icon name="checkCircle" [size]="22" />
                          <p>Rien à commander : le stock couvre la demande prévue.</p>
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        } @else {
          <section class="card">
            <div class="card-header">
              <h2 class="card-title">Historique des commandes</h2>
              <span class="badge">{{ orders().length }}</span>
            </div>
            <div class="overflow-x-auto">
              <table class="data-table">
                <thead class="whitespace-nowrap">
                  <tr>
                    <th>N°</th>
                    <th>Fournisseur</th>
                    <th>Statut</th>
                    <th>Créée</th>
                    <th class="num text-right">Montant</th>
                    <th><span class="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  @for (o of orders(); track o.id) {
                    <tr>
                      <td class="font-mono text-xs font-medium">{{ o.number }}</td>
                      <td class="min-w-40">{{ o.supplierName }}</td>
                      <td>
                        <span class="badge" [class]="orderBadge(o.status)">
                          <span class="dot"></span>{{ orderLabel(o.status) }}
                        </span>
                      </td>
                      <td class="whitespace-nowrap text-muted">
                        {{ o.createdAt | date: 'dd/MM HH:mm' }}
                      </td>
                      <td class="num whitespace-nowrap font-medium">{{ o.totalCents | money }}</td>
                      <td>
                        <div class="flex justify-end gap-1.5">
                          <button type="button" class="btn btn-ghost btn-sm" (click)="copyText(o)">
                            <app-icon name="copy" [size]="14" />
                            Copier le texte
                          </button>
                          @if (o.status === 'DRAFT') {
                            <button
                              type="button"
                              class="btn btn-primary btn-sm"
                              *appHasPermission="'PROCUREMENT_MANAGE'"
                              (click)="send(o)"
                            >
                              <app-icon name="send" [size]="14" />
                              Envoyer
                            </button>
                          }
                        </div>
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6">
                        <div class="empty-state">
                          <app-icon name="procurement" [size]="22" />
                          <p>Aucune commande</p>
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }
      </div>
    </div>
  `,
})
export class OrderSheetPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/procurement`;

  protected readonly tab = signal<'proposal' | 'orders'>('proposal');
  protected readonly suppliers = signal<Supplier[]>([]);
  protected readonly sheet = signal<ProposalSheet | null>(null);
  protected readonly quantities = signal<Record<string, number>>({});
  protected readonly orders = signal<PurchaseOrder[]>([]);
  protected readonly status = signal<string | null>(null);
  protected readonly selectedCount = computed(
    () => Object.values(this.quantities()).filter((q) => q > 0).length,
  );
  protected supplierId: string | null = null;

  constructor() {
    void this.init();
  }

  protected reasons(line: ProposalLine): string {
    return line.reasons.map((r) => REASON_LABELS[r] ?? r).join(', ');
  }

  protected reasonLabel(reason: string): string {
    return REASON_LABELS[reason] ?? reason;
  }

  protected reasonBadge(reason: string): string {
    return REASON_BADGE[reason] ?? '';
  }

  protected orderLabel(status: PurchaseOrder['status']): string {
    return ORDER_STATUS[status]?.label ?? status;
  }

  protected orderBadge(status: PurchaseOrder['status']): string {
    return ORDER_STATUS[status]?.badge ?? '';
  }

  protected toggle(line: ProposalLine, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.setQuantity(line, checked ? line.suggestedQuantity : 0);
  }

  protected setQuantity(line: ProposalLine, value: number): void {
    this.quantities.update((q) => ({ ...q, [line.productId]: Math.max(Number(value) || 0, 0) }));
  }

  protected async loadProposal(): Promise<void> {
    const params: Record<string, string> = this.supplierId ? { supplierId: this.supplierId } : {};
    const sheet = await firstValueFrom(
      this.http.get<ProposalSheet>(`${this.api}/proposal`, { params }),
    );
    this.sheet.set(sheet);
    this.quantities.set(
      Object.fromEntries(sheet.lines.map((l) => [l.productId, l.suggestedQuantity])),
    );
  }

  protected async createOrder(): Promise<void> {
    const sheet = this.sheet();
    if (!sheet || !this.supplierId) {
      return;
    }
    const lines = sheet.lines
      .filter((l) => (this.quantities()[l.productId] ?? 0) > 0)
      .map((l) => ({
        productId: l.productId,
        quantity: this.quantities()[l.productId],
        unitCostCents: l.purchasePriceCents,
        suggestedQuantity: l.suggestedQuantity,
        reason: this.reasons(l),
      }));
    try {
      const order = await firstValueFrom(
        this.http.post<PurchaseOrder>(`${this.api}/orders`, {
          supplierId: this.supplierId,
          lines,
          notes: null,
        }),
      );
      this.status.set(`Commande ${order.number} créée (brouillon)`);
      await this.openOrders();
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Création refusée',
      );
    }
  }

  protected async openOrders(): Promise<void> {
    this.tab.set('orders');
    this.orders.set(await firstValueFrom(this.http.get<PurchaseOrder[]>(`${this.api}/orders`)));
  }

  protected async send(order: PurchaseOrder): Promise<void> {
    try {
      await firstValueFrom(this.http.post(`${this.api}/orders/${order.id}/send`, {}));
      this.status.set(`Commande ${order.number} envoyée`);
      await this.openOrders();
    } catch (error) {
      this.status.set((error as { error?: { detail?: string } }).error?.detail ?? 'Envoi refusé');
    }
  }

  protected async copyText(order: PurchaseOrder): Promise<void> {
    const text = await firstValueFrom(
      this.http.get(`${this.api}/orders/${order.id}/text`, { responseType: 'text' }),
    );
    try {
      await navigator.clipboard.writeText(text);
      this.status.set('Texte de la commande copié : collez-le dans WhatsApp ou un e-mail.');
    } catch {
      window.prompt('Copiez le texte de la commande', text);
    }
  }

  private async init(): Promise<void> {
    this.suppliers.set(await firstValueFrom(this.http.get<Supplier[]>(`${this.api}/suppliers`)));
    await this.loadProposal();
  }
}
