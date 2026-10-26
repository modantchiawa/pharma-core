import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ProposalLine, ProposalSheet, PurchaseOrder, Supplier } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { MoneyPipe } from '../../shared/money.pipe';

const REASON_LABELS: Record<string, string> = {
  BELOW_TARGET: 'sous la couverture',
  BELOW_THRESHOLD: 'sous le seuil',
  EXPIRING_REPLACEMENT: 'péremption proche',
  LOST_SALES: 'ventes perdues',
};

/**
 * Tomorrow's order sheet: the system proposes, the pharmacist adjusts quantities, then the
 * order is sent (text for WhatsApp/e-mail, or printed) and later received lot by lot.
 */
@Component({
  selector: 'app-order-sheet-page',
  imports: [FormsModule, MoneyPipe, DatePipe, DecimalPipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 p-4">
      <nav class="flex gap-2" role="tablist">
        <button
          type="button"
          role="tab"
          class="rounded px-3 py-1"
          [class.bg-brand]="tab() === 'proposal'"
          [class.text-on-brand]="tab() === 'proposal'"
          (click)="tab.set('proposal')"
        >
          Fiche de commande
        </button>
        <button
          type="button"
          role="tab"
          class="rounded px-3 py-1"
          [class.bg-brand]="tab() === 'orders'"
          [class.text-on-brand]="tab() === 'orders'"
          (click)="openOrders()"
        >
          Commandes
        </button>
      </nav>

      @if (tab() === 'proposal') {
        <div class="flex flex-wrap items-center gap-2">
          <select
            class="rounded border px-2 py-1"
            [(ngModel)]="supplierId"
            (ngModelChange)="loadProposal()"
          >
            <option [ngValue]="null">Tous les fournisseurs</option>
            @for (s of suppliers(); track s.id) {
              <option [ngValue]="s.id">{{ s.details.name }}</option>
            }
          </select>
          <button type="button" class="rounded border px-3 py-1" (click)="loadProposal()">
            Recalculer
          </button>
          @if (sheet(); as sheet) {
            <span class="text-sm text-slate-600"
              >Pour le {{ sheet.date | date: 'dd/MM/yyyy' }} · vitesse de vente sur
              {{ sheet.periodDays }} jours · estimation
              {{ sheet.estimatedTotalCents | money }}</span
            >
          }
          <button
            type="button"
            class="ml-auto rounded bg-brand px-3 py-1 text-on-brand"
            *appHasPermission="'PROCUREMENT_MANAGE'"
            [disabled]="!supplierId || selectedCount() === 0"
            (click)="createOrder()"
          >
            Créer la commande ({{ selectedCount() }} lignes)
          </button>
        </div>
        @if (!supplierId) {
          <p class="text-sm text-amber-800">
            Choisissez un fournisseur pour transformer la proposition en commande.
          </p>
        }
        <table class="w-full rounded border bg-white text-sm">
          <thead class="bg-slate-100 text-left">
            <tr>
              <th class="p-2"></th>
              <th class="p-2">Produit</th>
              <th class="p-2">Fournisseur</th>
              <th class="p-2 text-right">Dispo</th>
              <th class="p-2 text-right">En cmd</th>
              <th class="p-2 text-right">Vente/j</th>
              <th class="p-2 text-right">Cible</th>
              <th class="p-2 text-right">Proposé</th>
              <th class="p-2 text-right">À commander</th>
              <th class="p-2">Motif</th>
            </tr>
          </thead>
          <tbody>
            @for (line of sheet()?.lines ?? []; track line.productId) {
              <tr class="border-t">
                <td class="p-2">
                  <input
                    type="checkbox"
                    [checked]="quantities()[line.productId] > 0"
                    (change)="toggle(line, $event)"
                  />
                </td>
                <td class="p-2">
                  {{ line.designation }} <small class="text-slate-500">{{ line.cip13 }}</small>
                </td>
                <td class="p-2">{{ line.supplierName ?? '—' }}</td>
                <td class="p-2 text-right">
                  {{ line.available }}
                  @if (line.expiringSoon > 0) {
                    <small class="text-amber-700">(-{{ line.expiringSoon }} périm.)</small>
                  }
                </td>
                <td class="p-2 text-right">{{ line.onOrder }}</td>
                <td class="p-2 text-right">{{ line.dailyDemand | number: '1.1-1' }}</td>
                <td class="p-2 text-right">{{ line.targetStock }}</td>
                <td class="p-2 text-right">
                  {{ line.suggestedQuantity }}
                  <small class="text-slate-500">(×{{ line.packSize }})</small>
                </td>
                <td class="p-2 text-right">
                  <input
                    type="number"
                    min="0"
                    [step]="line.packSize"
                    class="w-20 rounded border px-1 text-right"
                    [ngModel]="quantities()[line.productId]"
                    (ngModelChange)="setQuantity(line, $event)"
                  />
                </td>
                <td class="p-2 text-xs text-slate-600">{{ reasons(line) }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="10" class="p-6 text-center text-slate-500">
                  Rien à commander : le stock couvre la demande prévue.
                </td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <table class="w-full rounded border bg-white text-sm">
          <thead class="bg-slate-100 text-left">
            <tr>
              <th class="p-2">N°</th>
              <th class="p-2">Fournisseur</th>
              <th class="p-2">Statut</th>
              <th class="p-2">Créée</th>
              <th class="p-2 text-right">Montant</th>
              <th class="p-2"></th>
            </tr>
          </thead>
          <tbody>
            @for (o of orders(); track o.id) {
              <tr class="border-t">
                <td class="p-2">{{ o.number }}</td>
                <td class="p-2">{{ o.supplierName }}</td>
                <td class="p-2">{{ o.status }}</td>
                <td class="p-2">{{ o.createdAt | date: 'dd/MM HH:mm' }}</td>
                <td class="p-2 text-right">{{ o.totalCents | money }}</td>
                <td class="p-2 text-right">
                  <button type="button" class="underline" (click)="copyText(o)">
                    Copier le texte
                  </button>
                  @if (o.status === 'DRAFT') {
                    <button
                      type="button"
                      class="ml-2 rounded bg-brand px-2 text-on-brand"
                      *appHasPermission="'PROCUREMENT_MANAGE'"
                      (click)="send(o)"
                    >
                      Envoyer
                    </button>
                  }
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="6" class="p-6 text-center text-slate-500">Aucune commande</td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (status(); as text) {
        <p class="text-sm" role="status">{{ text }}</p>
      }
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
