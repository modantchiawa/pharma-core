import { describe, expect, it } from 'vitest';
import { ProposalLine, PurchaseOrder, Supplier } from '../../core/api/models';
import { parseGs1 } from '../../shared/gs1';
import {
  applyGs1,
  daysLate,
  generationPreview,
  mailtoLink,
  orderStatus,
  orderTimeline,
  receiptDraft,
  receiptLineProblem,
  receivedPercent,
  whatsappLink,
} from './purchase-orders';

function order(overrides: Partial<PurchaseOrder> = {}): PurchaseOrder {
  return {
    id: 'o1',
    number: 'CMD-2026-00012',
    supplierId: 's1',
    supplierName: 'Laborex Niger',
    status: 'SENT',
    createdAt: '2026-10-20T08:00:00Z',
    sentAt: '2026-10-20T09:00:00Z',
    expectedAt: '2026-10-25',
    totalCents: 45_000,
    notes: null,
    lines: [
      {
        lineNo: 1,
        productId: 'p1',
        designation: 'DOLIPRANE 1000MG CPR B/8',
        cip13: '3400990000011',
        quantityOrdered: 30,
        quantityReceived: 10,
        unitCostCents: 900,
        packSize: 1,
        suggestedQuantity: 30,
        reason: null,
        locationCode: 'A1',
        locationName: 'Rayon A · antalgiques',
        locationKind: 'SHELF',
        locationDetail: 'case 1',
        imageVersion: null,
      },
      {
        lineNo: 2,
        productId: 'p2',
        designation: 'COARTEM 80/480MG CPR B/6',
        cip13: '3400990000028',
        quantityOrdered: 20,
        quantityReceived: 20,
        unitCostCents: 1_870,
        packSize: 1,
        suggestedQuantity: 20,
        reason: null,
        locationCode: null,
        locationName: null,
        locationKind: null,
        locationDetail: null,
        imageVersion: null,
      },
    ],
    closedAt: null,
    overdue: true,
    shortClosed: false,
    orderedUnits: 50,
    receivedUnits: 30,
    receivedCents: 46_400,
    remainingCents: 18_000,
    supplier: {
      contactName: null,
      phone: '+227 20 74 00 00',
      whatsapp: '+227 90 74 00 00',
      email: 'commandes@laborex.ne',
      minOrderCents: 250_000,
      leadTimeDays: 1,
    },
    ...overrides,
  };
}

function proposalLine(
  productId: string,
  supplierId: string | null,
  quantity: number,
  price: number | null,
): ProposalLine {
  return {
    productId,
    cip13: '',
    designation: productId,
    supplierId,
    supplierName: supplierId ? `Fournisseur ${supplierId}` : null,
    available: 0,
    onOrder: 0,
    expiringSoon: 0,
    soldLastPeriod: 0,
    shortfall: 0,
    dailyDemand: 0,
    targetStock: 0,
    suggestedQuantity: quantity,
    packSize: 1,
    purchasePriceCents: price,
    reasons: [],
  };
}

function supplier(id: string, minOrderCents: number): Supplier {
  return {
    id,
    code: id,
    active: true,
    details: {
      name: `Fournisseur ${id}`,
      contactName: null,
      phone: null,
      whatsapp: null,
      email: null,
      address: null,
      leadTimeDays: 1,
      orderDays: null,
      minOrderCents,
      paymentTerms: null,
      notes: null,
    },
  };
}

describe('purchase order helpers', () => {
  it('builds a WhatsApp link from any phone notation', () => {
    expect(whatsappLink('+227 90 74 00 00', 'Commande CMD-1\n2 x Doliprane')).toBe(
      'https://wa.me/22790740000?text=Commande%20CMD-1%0A2%20x%20Doliprane',
    );
    expect(whatsappLink('00227-90-74-00-00', 'a&b')).toBe('https://wa.me/22790740000?text=a%26b');
    expect(whatsappLink(null, 'x')).toBeNull();
    expect(whatsappLink('12', 'x')).toBeNull();
  });

  it('builds a mailto link with subject and body', () => {
    expect(mailtoLink(' commandes@laborex.ne ', 'Commande CMD-1', 'Bonjour,\nmerci')).toBe(
      'mailto:commandes@laborex.ne?subject=Commande%20CMD-1&body=Bonjour%2C%0Amerci',
    );
    expect(mailtoLink('', 's', 'b')).toBeNull();
  });

  it('counts the days of delay against the pharmacy day', () => {
    expect(daysLate('2026-10-25', '2026-10-27')).toBe(2);
    expect(daysLate('2026-10-31', '2026-11-02')).toBe(2);
    expect(daysLate('2026-10-27', '2026-10-27')).toBe(0);
    expect(daysLate('2026-10-28', '2026-10-27')).toBe(0);
    expect(daysLate(null, '2026-10-27')).toBe(0);
  });

  it('labels statuses, short-closed orders read "Soldée"', () => {
    expect(orderStatus({ status: 'DRAFT', shortClosed: false }).label).toBe('Brouillon');
    expect(orderStatus({ status: 'PARTIALLY_RECEIVED', shortClosed: false })).toEqual({
      label: 'Reçue en partie',
      badge: 'badge-warning',
    });
    expect(orderStatus({ status: 'RECEIVED', shortClosed: false }).label).toBe('Reçue');
    expect(orderStatus({ status: 'RECEIVED', shortClosed: true }).label).toBe('Soldée');
    expect(orderStatus({ status: 'CANCELLED', shortClosed: false }).badge).toBe('badge-danger');
  });

  it('previews one order per supplier with the minimum warning and unassigned lines', () => {
    const preview = generationPreview(
      [
        proposalLine('a', 's1', 10, 1_000),
        proposalLine('b', 's2', 5, 30_000),
        proposalLine('c', 's1', 4, 500),
        proposalLine('d', null, 3, null),
        proposalLine('e', 's2', 8, 100),
      ],
      { c: 6, e: 0 },
      [supplier('s1', 50_000), supplier('s2', 100_000)],
    );
    expect(preview.suppliers).toEqual([
      expect.objectContaining({
        supplierId: 's1',
        lineCount: 2,
        units: 16,
        estimatedCents: 13_000,
        belowMinimum: true,
      }),
      expect.objectContaining({
        supplierId: 's2',
        lineCount: 1,
        units: 5,
        estimatedCents: 150_000,
        belowMinimum: false,
      }),
    ]);
    expect(preview.unassigned.map((l) => l.productId)).toEqual(['d']);
    expect(preview.totalCents).toBe(163_000);
  });

  it('pre-fills a delivery with the remaining quantities and fills lot and expiry from a scan', () => {
    const lines = receiptDraft(order(), (minor) => minor);
    expect(lines).toHaveLength(1);
    expect(lines[0]).toEqual(
      expect.objectContaining({ productId: 'p1', remaining: 20, quantity: 20, unitCost: '900' }),
    );
    expect(receiptLineProblem(lines[0], '2026-10-27')).toBe('numéro de lot manquant');

    const scanned = parseGs1('010340099000001117280331' + '10LOT42');
    expect(applyGs1(lines, scanned!)).toBe(0);
    expect(lines[0].lotNumber).toBe('LOT42');
    expect(lines[0].expiryDate).toBe('2028-03-31');
    expect(receiptLineProblem(lines[0], '2026-10-27')).toBeNull();
    expect(applyGs1(lines, parseGs1('01034009999999991728033110X')!)).toBe(-1);

    lines[0].quantity = 0;
    lines[0].lotNumber = '';
    expect(receiptLineProblem(lines[0], '2026-10-27')).toBeNull();
  });

  it('draws the timeline from creation to the short closure', () => {
    const late = orderTimeline(order(), [], '2026-10-27');
    expect(late.map((s) => s.label)).toEqual(['Créée', 'Envoyée', 'Livraison prévue']);
    expect(late[2]).toEqual(expect.objectContaining({ state: 'late', detail: 'En retard de 2 j' }));

    const closed = orderTimeline(
      order({
        status: 'RECEIVED',
        shortClosed: true,
        overdue: false,
        closedAt: '2026-10-27T10:00:00Z',
      }),
      [
        {
          id: 'r1',
          receivedAt: '2026-10-26T10:00:00Z',
          supplierInvoiceNumber: 'F-1',
          invoiceDate: '2026-10-26',
          totalExclVatCents: 46_400,
          vatCents: 0,
          lines: 2,
          units: 30,
          receivedBy: 'Dr Maïga',
        },
      ],
      '2026-10-27',
    );
    expect(closed.map((s) => s.label)).toEqual([
      'Créée',
      'Envoyée',
      'Livraison prévue',
      'Réception',
      'Soldée',
    ]);
    expect(closed[3].detail).toBe('30 unité(s), 2 ligne(s) · facture F-1 · Dr Maïga');
    expect(closed[4].detail).toBe('20 unité(s) jamais livrée(s)');

    const draft = orderTimeline(
      order({ status: 'DRAFT', sentAt: null, expectedAt: null }),
      [],
      '2026-10-27',
    );
    expect(draft.map((s) => s.state)).toEqual(['done', 'upcoming']);
  });

  it('computes the received share for the progress bar', () => {
    expect(receivedPercent(50, 30)).toBe(60);
    expect(receivedPercent(0, 0)).toBe(0);
    expect(receivedPercent(10, 12)).toBe(100);
  });
});
