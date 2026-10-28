import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { PermissionService } from '../../core/auth/permission.service';
import { CachedProduct } from '../../core/offline/pos-database';
import { CartStore } from './cart.store';

function product(overrides: Partial<CachedProduct> = {}): CachedProduct {
  return {
    productId: 'p-1',
    cip13: '3400930000001',
    ean13: null,
    designation: 'DOLIPRANE 1000MG',
    designationKey: 'DOLIPRANE 1000MG',
    dci: 'PARACETAMOL',
    listing: 'NONE',
    vatRate: 2.1,
    unitPriceCents: 250,
    availableQuantity: 5,
    active: true,
    changedAt: '2026-10-18T00:00:00Z',
    ...overrides,
  };
}

describe('CartStore', () => {
  const permissions = new Set<string>(['SALE_CREATE']);
  const maxDiscount = signal(5);

  beforeEach(() => {
    permissions.clear();
    permissions.add('SALE_CREATE');
    maxDiscount.set(5);
    TestBed.configureTestingModule({
      providers: [
        CartStore,
        {
          provide: PermissionService,
          useValue: {
            profile: signal(null),
            maxDiscountPercent: maxDiscount,
            has: (permission: string) => permissions.has(permission),
          },
        },
      ],
    });
  });

  it('merges scans of the same product and computes totals', () => {
    const cart = TestBed.inject(CartStore);
    cart.add(product());
    cart.add(product());
    cart.setDiscountPercent('p-1', 4);

    expect(cart.lines()).toHaveLength(1);
    expect(cart.grossCents()).toBe(500);
    expect(cart.discountCents()).toBe(20);
    expect(cart.netCents()).toBe(480);
  });

  it('blocks a discount above the role ceiling unless overridden (ABAC)', () => {
    const cart = TestBed.inject(CartStore);
    cart.add(product());
    cart.setDiscountPercent('p-1', 10);
    cart.addPayment({ method: 'CASH', amountCents: 225 });

    expect(cart.blockingIssues().some((issue) => issue.includes('plafond'))).toBe(true);
    expect(cart.canCheckout()).toBe(false);

    permissions.add('SALE_DISCOUNT_OVERRIDE');
    cart.setDiscountPercent('p-1', 10);
    expect(cart.canCheckout()).toBe(true);
  });

  it('requires dispensing right and prescriber for listed drugs', () => {
    const cart = TestBed.inject(CartStore);
    cart.add(product({ productId: 'abx', listing: 'LIST_I', designation: 'AMOXICILLINE' }));
    cart.addPayment({ method: 'THIRD_PARTY_PAYER', amountCents: 250 });

    expect(cart.blockingIssues()).toHaveLength(2);

    permissions.add('PRESCRIPTION_DISPENSE');
    cart.prescription.set({ prescriberName: 'Dr Martin' });
    expect(cart.blockingIssues()).toEqual([]);
  });

  it('applies non-cash payments first and returns change on cash only', () => {
    const cart = TestBed.inject(CartStore);
    cart.add(product({ unitPriceCents: 1_250 }));
    cart.addPayment({ method: 'CASH', amountCents: 2_000 });
    cart.addPayment({ method: 'THIRD_PARTY_PAYER', amountCents: 1_000, thirdPartyCode: 'AMO' });

    expect(cart.changeDueCents()).toBe(1_750);
    expect(cart.appliedPayments()).toEqual([
      { method: 'THIRD_PARTY_PAYER', amountCents: 1_000, thirdPartyCode: 'AMO' },
      { method: 'CASH', amountCents: 250 },
    ]);
  });

  it('keeps where the box is stored under the line, also for entries cached without it', () => {
    const cart = TestBed.inject(CartStore);
    cart.add(
      product({
        locationCode: 'A3',
        locationName: 'Rayon A · étagère 3',
        locationKind: 'SHELF',
        locationDetail: 'case 12',
      }),
    );
    cart.add(product({ productId: 'p-2' }));

    expect(cart.lines()[0].place).toEqual({
      code: 'A3',
      name: 'Rayon A · étagère 3',
      kind: 'SHELF',
      detail: 'case 12',
    });
    expect(cart.lines()[1].place).toBeNull();
  });

  it('blocks a contraindication until a pharmacist overrides it with a reason', () => {
    const cart = TestBed.inject(CartStore);
    cart.add(product());
    cart.addPayment({ method: 'CASH', amountCents: 250 });
    cart.setSafetyAlerts([
      {
        kind: 'ALLERGY',
        severity: 'CONTRAINDICATED',
        title: 'Allergie déclarée (Pénicillines) : AMOXICILLINE',
        detail: '',
        productIds: ['p-1'],
        key: 'ALLERGY:PENICILLINS:p-1',
      },
      {
        kind: 'INTERACTION',
        severity: 'PRECAUTION',
        title: 'Interaction : A + B',
        detail: '',
        productIds: ['p-1'],
        key: 'INTERACTION:A|B:p-1',
      },
    ]);

    expect(cart.blockingSafety()).toHaveLength(1);
    expect(cart.canCheckout()).toBe(false);
    // a cashier cannot take the responsibility
    expect(cart.overrideSafety('ALLERGY:PENICILLINS:p-1', 'Allergie infirmée')).toBe(false);

    permissions.add('SAFETY_OVERRIDE');
    expect(cart.overrideSafety('ALLERGY:PENICILLINS:p-1', '  ')).toBe(false);
    expect(cart.overrideSafety('ALLERGY:PENICILLINS:p-1', 'Allergie infirmée ')).toBe(true);
    expect(cart.canCheckout()).toBe(true);
    expect(cart.appliedSafetyOverrides()).toEqual([
      { alertKey: 'ALLERGY:PENICILLINS:p-1', reason: 'Allergie infirmée' },
    ]);

    // the alert disappears (product removed): its override is forgotten, nothing is sent
    cart.setSafetyAlerts([]);
    expect(cart.appliedSafetyOverrides()).toEqual([]);
    expect(cart.safetyOverrides().size).toBe(0);
  });

  it('warns when the local stock is lower than the quantity sold', () => {
    const cart = TestBed.inject(CartStore);
    cart.add(product({ availableQuantity: 1 }), 3);

    expect(cart.stockWarnings()).toHaveLength(1);
  });
});
