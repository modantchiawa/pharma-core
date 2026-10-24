import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Customer,
  DrugListing,
  InsurancePolicy,
  PaymentMethod,
  Prescription,
  PrescriptionRequest,
  insuredShareCents,
} from '../../core/api/models';
import { PermissionService } from '../../core/auth/permission.service';
import { CachedProduct } from '../../core/offline/pos-database';

export interface CartLine {
  productId: string;
  designation: string;
  cip13: string;
  listing: DrugListing;
  vatRate: number;
  unitPriceCents: number;
  quantity: number;
  discountCents: number;
  availableQuantity: number;
}

export interface CartPayment {
  method: PaymentMethod;
  amountCents: number;
  thirdPartyCode?: string;
  provider?: string;
  /** Insurance policy covering a THIRD_PARTY_PAYER share. */
  policyId?: string;
}

const DISPENSING_PERMISSION: Record<DrugListing, string | null> = {
  NONE: null,
  LIST_I: 'PRESCRIPTION_DISPENSE',
  LIST_II: 'PRESCRIPTION_DISPENSE',
  STUPEFIANT: 'NARCOTIC_DISPENSE',
};

/**
 * Signal store of the current basket. It applies the same rules as the server (discount
 * ceiling, dispensing rights, prescription, payment balance) so the cashier is warned before
 * the sale is committed, including offline where the server cannot refuse anything.
 */
@Injectable()
export class CartStore {
  private readonly permissions = inject(PermissionService);

  readonly lines = signal<CartLine[]>([]);
  readonly payments = signal<CartPayment[]>([]);
  readonly prescription = signal<PrescriptionRequest | null>(null);
  readonly customer = signal<Customer | null>(null);
  /** Policy the cashier chose for the third-party payer share, among the customer's covers. */
  readonly policy = signal<InsurancePolicy | null>(null);
  /** Renewable prescription on file the dispensing counts against. */
  readonly prescriptionOnFile = signal<Prescription | null>(null);

  readonly grossCents = computed(() =>
    this.lines().reduce((sum, l) => sum + l.unitPriceCents * l.quantity, 0),
  );
  readonly discountCents = computed(() =>
    this.lines().reduce((sum, l) => sum + l.discountCents, 0),
  );
  readonly netCents = computed(() => this.grossCents() - this.discountCents());
  readonly paidCents = computed(() => this.payments().reduce((sum, p) => sum + p.amountCents, 0));
  readonly remainingCents = computed(() => Math.max(this.netCents() - this.paidCents(), 0));
  readonly changeDueCents = computed(() => {
    const cash = this.payments()
      .filter((p) => p.method === 'CASH')
      .reduce((sum, p) => sum + p.amountCents, 0);
    return Math.min(Math.max(this.paidCents() - this.netCents(), 0), cash);
  });
  readonly creditCents = computed(() =>
    this.payments()
      .filter((p) => p.method === 'CREDIT')
      .reduce((sum, p) => sum + p.amountCents, 0),
  );
  readonly itemCount = computed(() => this.lines().reduce((sum, l) => sum + l.quantity, 0));
  readonly requiresPrescription = computed(() => this.lines().some((l) => l.listing !== 'NONE'));
  /** What the chosen policy covers on the current total: the server applies the same rule. */
  readonly insuredShareCents = computed(() => {
    const policy = this.policy();
    return policy ? insuredShareCents(policy, this.netCents()) : 0;
  });
  readonly insuredCents = computed(() =>
    this.payments()
      .filter((p) => p.method === 'THIRD_PARTY_PAYER' && p.policyId)
      .reduce((sum, p) => sum + p.amountCents, 0),
  );

  readonly vatBreakdown = computed(() => {
    const byRate = new Map<number, number>();
    for (const line of this.lines()) {
      const net = line.unitPriceCents * line.quantity - line.discountCents;
      byRate.set(line.vatRate, (byRate.get(line.vatRate) ?? 0) + net);
    }
    return [...byRate.entries()]
      .sort(([a], [b]) => a - b)
      .map(([rate, totalTtc]) => ({
        rate,
        totalTtc,
        vat: Math.round(totalTtc - totalTtc / (1 + rate / 100)),
      }));
  });

  readonly blockingIssues = computed(() => {
    const issues: string[] = [];
    const max = this.permissions.maxDiscountPercent();
    const override = this.permissions.has('SALE_DISCOUNT_OVERRIDE');
    for (const line of this.lines()) {
      if (!override && discountPercent(line) > max) {
        issues.push(`${line.designation} : remise supérieure à votre plafond (${max} %)`);
      }
      const required = DISPENSING_PERMISSION[line.listing];
      if (required && !this.permissions.has(required)) {
        issues.push(`${line.designation} : délivrance réservée (${required})`);
      }
    }
    if (this.requiresPrescription() && !this.prescription()?.prescriberName?.trim()) {
      issues.push('Ordonnance requise : renseignez le prescripteur');
    }
    if (this.lines().length > 0 && this.paidCents() < this.netCents()) {
      issues.push('Règlement incomplet');
    }
    const credit = this.creditCents();
    if (credit > 0) {
      const customer = this.customer();
      if (!customer) {
        issues.push('Vente à crédit : choisissez un client');
      } else if (!this.permissions.has('CREDIT_SALE')) {
        issues.push('Vous ne pouvez pas vendre à crédit');
      } else if (credit > customer.availableCreditCents) {
        issues.push(`Crédit disponible dépassé pour ${customer.displayName}`);
      }
    }
    const insured = this.insuredCents();
    if (insured > 0) {
      const policy = this.policy();
      if (!policy || this.customer()?.id !== policy.customerId) {
        issues.push('Tiers payant : la police ne correspond pas au client');
      } else if (insured !== this.insuredShareCents()) {
        issues.push(
          `Tiers payant : la part ${policy.insurerName} doit être de ${this.insuredShareCents() / 100}`,
        );
      }
    }
    const onFile = this.prescriptionOnFile();
    if (onFile && onFile.renewalsRemaining <= 0) {
      issues.push('Ordonnance épuisée : aucune délivrance restante');
    }
    return issues;
  });

  readonly stockWarnings = computed(() =>
    this.lines()
      .filter((l) => l.quantity > l.availableQuantity)
      .map((l) => `${l.designation} : ${l.availableQuantity} en stock connu localement`),
  );

  readonly canCheckout = computed(
    () => this.lines().length > 0 && this.blockingIssues().length === 0,
  );

  add(product: CachedProduct, quantity = 1): void {
    if (product.unitPriceCents === null) {
      throw new Error(`${product.designation} n'a pas de prix de vente`);
    }
    const price = product.unitPriceCents;
    this.lines.update((lines) => {
      const existing = lines.find((l) => l.productId === product.productId);
      if (existing) {
        return lines.map((l) => (l === existing ? { ...l, quantity: l.quantity + quantity } : l));
      }
      return [
        ...lines,
        {
          productId: product.productId,
          designation: product.designation,
          cip13: product.cip13,
          listing: product.listing,
          vatRate: product.vatRate,
          unitPriceCents: price,
          quantity,
          discountCents: 0,
          availableQuantity: product.availableQuantity,
        },
      ];
    });
  }

  setQuantity(productId: string, quantity: number): void {
    if (quantity <= 0) {
      this.remove(productId);
      return;
    }
    this.lines.update((lines) =>
      lines.map((l) =>
        l.productId === productId
          ? {
              ...l,
              quantity,
              discountCents: Math.min(l.discountCents, l.unitPriceCents * quantity),
            }
          : l,
      ),
    );
  }

  setDiscountPercent(productId: string, percent: number): void {
    const bounded = Math.min(Math.max(percent, 0), 100);
    this.lines.update((lines) =>
      lines.map((l) =>
        l.productId === productId
          ? { ...l, discountCents: Math.round((l.unitPriceCents * l.quantity * bounded) / 100) }
          : l,
      ),
    );
  }

  remove(productId: string): void {
    this.lines.update((lines) => lines.filter((l) => l.productId !== productId));
  }

  addPayment(payment: CartPayment): void {
    if (payment.amountCents > 0) {
      this.payments.update((payments) => [...payments, payment]);
    }
  }

  removePayment(index: number): void {
    this.payments.update((payments) => payments.filter((_, i) => i !== index));
  }

  /** Amounts applied to the sale: cash tendered beyond the total is change, not revenue. */
  appliedPayments(): CartPayment[] {
    let remaining = this.netCents();
    const nonCash = this.payments().filter((p) => p.method !== 'CASH');
    const cash = this.payments().filter((p) => p.method === 'CASH');
    const applied: CartPayment[] = [];
    for (const payment of [...nonCash, ...cash]) {
      const amount = Math.min(payment.amountCents, remaining);
      if (amount > 0) {
        applied.push({ ...payment, amountCents: amount });
        remaining -= amount;
      }
    }
    return applied;
  }

  /** Replaces any previous insured share by the one the chosen policy covers right now. */
  applyPolicy(policy: InsurancePolicy): void {
    this.policy.set(policy);
    const share = insuredShareCents(policy, this.netCents());
    this.payments.update((payments) => [
      ...payments.filter((p) => !(p.method === 'THIRD_PARTY_PAYER' && p.policyId)),
      ...(share > 0
        ? [
            {
              method: 'THIRD_PARTY_PAYER' as PaymentMethod,
              amountCents: share,
              policyId: policy.id,
            },
          ]
        : []),
    ]);
  }

  /** The prescription on file pre-fills the prescriber; the sale then counts as one dispensing. */
  usePrescriptionOnFile(prescription: Prescription | null): void {
    this.prescriptionOnFile.set(prescription);
    this.prescription.update((current) =>
      prescription
        ? {
            prescriberName: prescription.prescriberName,
            prescriberRpps: current?.prescriberRpps,
            patientReference: prescription.patientReference ?? current?.patientReference,
            prescriptionId: prescription.id,
          }
        : current
          ? { ...current, prescriptionId: undefined }
          : null,
    );
  }

  clear(): void {
    this.lines.set([]);
    this.payments.set([]);
    this.prescription.set(null);
    this.customer.set(null);
    this.policy.set(null);
    this.prescriptionOnFile.set(null);
  }
}

export function discountPercent(
  line: Pick<CartLine, 'unitPriceCents' | 'quantity' | 'discountCents'>,
): number {
  const gross = line.unitPriceCents * line.quantity;
  return gross === 0 ? 0 : Math.round((line.discountCents * 10_000) / gross) / 100;
}
