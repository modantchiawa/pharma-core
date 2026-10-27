import {
  GoodsReceiptResult,
  ProposalLine,
  PurchaseOrder,
  PurchaseOrderReceipt,
  PurchaseOrderScope,
  PurchaseOrderStatus,
  ReceivingView,
  Supplier,
} from '../../core/api/models';
import { Gs1Code } from '../../shared/gs1';

/** Pure helpers of the purchase order screens (labels, links, previews, timeline). */

export const ORDER_STATUS: Record<PurchaseOrderStatus, { label: string; badge: string }> = {
  DRAFT: { label: 'Brouillon', badge: '' },
  SENT: { label: 'Envoyée', badge: 'badge-info' },
  PARTIALLY_RECEIVED: { label: 'Reçue en partie', badge: 'badge-warning' },
  RECEIVED: { label: 'Reçue', badge: 'badge-success' },
  CANCELLED: { label: 'Annulée', badge: 'badge-danger' },
};

export const SCOPES: { value: PurchaseOrderScope; label: string }[] = [
  { value: 'OPEN', label: 'En cours' },
  { value: 'OVERDUE', label: 'En retard' },
  { value: 'DRAFTS', label: 'Brouillons' },
  { value: 'CLOSED', label: 'Clôturées' },
  { value: 'ALL', label: 'Toutes' },
];

/** Label and badge class of an order; a short-closed order reads "Soldée". */
export function orderStatus(order: Pick<PurchaseOrder, 'status' | 'shortClosed'>): {
  label: string;
  badge: string;
} {
  if (order.shortClosed) {
    return { label: 'Soldée', badge: 'badge-brand' };
  }
  return ORDER_STATUS[order.status] ?? { label: order.status, badge: '' };
}

/** Whole days between two ISO dates (YYYY-MM-DD), positive when {@code to} is later. */
export function daysBetween(from: string, to: string): number {
  const start = Date.UTC(+from.slice(0, 4), +from.slice(5, 7) - 1, +from.slice(8, 10));
  const end = Date.UTC(+to.slice(0, 4), +to.slice(5, 7) - 1, +to.slice(8, 10));
  return Math.round((end - start) / 86_400_000);
}

/** Days past the announced delivery day, 0 when not late (or no date). */
export function daysLate(expectedAt: string | null, today: string): number {
  return expectedAt ? Math.max(daysBetween(expectedAt, today), 0) : 0;
}

/** Today as an ISO date in the browser's time zone. */
export function isoToday(now = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * WhatsApp "click to chat" link: the number in international form, digits only (a leading 00
 * is the international prefix and is dropped), the message URL-encoded. Null without a number.
 */
export function whatsappLink(phone: string | null | undefined, text: string): string | null {
  let digits = (phone ?? '').replace(/\D/g, '');
  if (digits.startsWith('00')) {
    digits = digits.slice(2);
  }
  if (digits.length < 8) {
    return null;
  }
  return `https://wa.me/${digits}?text=${encodeURIComponent(text)}`;
}

/** E-mail link with subject and body; null without an address. */
export function mailtoLink(
  email: string | null | undefined,
  subject: string,
  body: string,
): string | null {
  const address = (email ?? '').trim();
  if (!address.includes('@')) {
    return null;
  }
  return `mailto:${address}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
}

export interface SupplierPreview {
  supplierId: string;
  supplierName: string;
  lineCount: number;
  units: number;
  estimatedCents: number;
  minOrderCents: number;
  belowMinimum: boolean;
}

export interface GenerationPreview {
  suppliers: SupplierPreview[];
  /** Lines to order that no supplier sells yet. */
  unassigned: ProposalLine[];
  totalCents: number;
}

/** What "Générer les commandes" will create: one order per supplier, as the server groups it. */
export function generationPreview(
  lines: ProposalLine[],
  quantities: Record<string, number>,
  suppliers: Supplier[],
): GenerationPreview {
  const bySupplier = new Map<string, SupplierPreview>();
  const unassigned: ProposalLine[] = [];
  for (const line of lines) {
    const quantity = quantities[line.productId] ?? line.suggestedQuantity;
    if (quantity <= 0) {
      continue;
    }
    if (!line.supplierId) {
      unassigned.push(line);
      continue;
    }
    let preview = bySupplier.get(line.supplierId);
    if (!preview) {
      const supplier = suppliers.find((s) => s.id === line.supplierId);
      preview = {
        supplierId: line.supplierId,
        supplierName: line.supplierName ?? supplier?.details.name ?? '—',
        lineCount: 0,
        units: 0,
        estimatedCents: 0,
        minOrderCents: supplier?.details.minOrderCents ?? 0,
        belowMinimum: false,
      };
      bySupplier.set(line.supplierId, preview);
    }
    preview.lineCount += 1;
    preview.units += quantity;
    preview.estimatedCents += (line.purchasePriceCents ?? 0) * quantity;
  }
  const previews = [...bySupplier.values()].map((p) => ({
    ...p,
    belowMinimum: p.estimatedCents < p.minOrderCents,
  }));
  return {
    suppliers: previews,
    unassigned,
    totalCents: previews.reduce((sum, p) => sum + p.estimatedCents, 0),
  };
}

/** One editable line of a delivery, pre-filled with what is still awaited. */
export interface ReceiptLineDraft {
  productId: string;
  designation: string;
  cip13: string;
  /** Units still awaited; null when the pharmacy receives blind (never shown to the receiver). */
  remaining: number | null;
  quantity: number;
  lotNumber: string;
  expiryDate: string;
  /** Typed amounts, in currency units ("1250"). */
  unitCost: string;
  vatRate: string;
  sellingPrice: string;
}

/**
 * Lines of the delivery form: pre-filled with what remains, or empty in blind receiving (the
 * receiver counts what came; the difference with the order is computed after the receipt).
 */
export function receiptDraft(
  view: ReceivingView,
  toUnits: (minor: number) => number,
): ReceiptLineDraft[] {
  return view.lines
    .filter((line) => view.blind || (line.quantityRemaining ?? 0) > 0)
    .map((line) => ({
      productId: line.productId,
      designation: line.designation,
      cip13: line.cip13,
      remaining: view.blind ? null : line.quantityRemaining,
      quantity: view.blind ? 0 : (line.quantityRemaining ?? 0),
      lotNumber: '',
      expiryDate: '',
      unitCost: String(toUnits(line.unitCostCents)),
      vatRate: '',
      sellingPrice: '',
    }));
}

/** What the receiver is told once a delivery is booked against an order. */
export function receiptOutcome(
  result: GoodsReceiptResult,
  units: number,
  orderNumber: string,
): string {
  const check = result.check;
  if (check?.blind && check.discrepancyLines > 0) {
    return `Réception enregistrée : ${units} unité(s). ${check.discrepancyLines} ligne(s) diffèrent de la commande, le titulaire en est informé.`;
  }
  if (result.orderStatus === 'RECEIVED') {
    return `Commande ${orderNumber} reçue en totalité`;
  }
  return `Réception enregistrée : ${units} unité(s) en stock`;
}

/** A line is sent when something was received; it then needs a lot and an expiry date. */
export function receiptLineProblem(line: ReceiptLineDraft, today: string): string | null {
  if (!(line.quantity > 0)) {
    return null;
  }
  if (!line.lotNumber.trim()) {
    return 'numéro de lot manquant';
  }
  if (!line.expiryDate) {
    return 'date de péremption manquante';
  }
  if (line.expiryDate <= today) {
    return 'lot déjà périmé';
  }
  return null;
}

/**
 * Applies a scanned GS1 DataMatrix to the matching line (same CIP/EAN-13): lot and expiry are
 * filled in. Returns the index of the line, or -1 when the box is not on this order.
 */
export function applyGs1(lines: ReceiptLineDraft[], code: Gs1Code): number {
  const index = lines.findIndex((line) => !!code.ean13 && line.cip13 === code.ean13);
  if (index >= 0) {
    if (code.lot) {
      lines[index].lotNumber = code.lot;
    }
    if (code.expiryDate) {
      lines[index].expiryDate = code.expiryDate;
    }
  }
  return index;
}

export type TimelineState = 'done' | 'late' | 'upcoming' | 'cancelled';

export interface TimelineStep {
  label: string;
  /** ISO instant or date; null when not reached yet. */
  at: string | null;
  /** Only a date is known (delivery day), no time. */
  dateOnly: boolean;
  detail: string | null;
  state: TimelineState;
}

/** créée → envoyée → livraison prévue → réceptions → soldée / reçue / annulée. */
export function orderTimeline(
  order: PurchaseOrder,
  receipts: PurchaseOrderReceipt[],
  today: string,
): TimelineStep[] {
  const steps: TimelineStep[] = [
    { label: 'Créée', at: order.createdAt, dateOnly: false, detail: null, state: 'done' },
  ];
  if (order.status === 'DRAFT') {
    steps.push({
      label: 'À envoyer au fournisseur',
      at: null,
      dateOnly: false,
      detail: null,
      state: 'upcoming',
    });
    return steps;
  }
  if (order.sentAt) {
    steps.push({
      label: 'Envoyée',
      at: order.sentAt,
      dateOnly: false,
      detail: null,
      state: 'done',
    });
  }
  const open = order.status === 'SENT' || order.status === 'PARTIALLY_RECEIVED';
  if (order.expectedAt) {
    const late = daysLate(order.expectedAt, today);
    steps.push({
      label: 'Livraison prévue',
      at: order.expectedAt,
      dateOnly: true,
      detail: open && order.overdue ? `En retard de ${Math.max(late, 1)} j` : null,
      state: open ? (order.overdue ? 'late' : 'upcoming') : 'done',
    });
  }
  receipts.forEach((receipt, index) => {
    const invoice = receipt.supplierInvoiceNumber
      ? ` · facture ${receipt.supplierInvoiceNumber}`
      : '';
    steps.push({
      label: receipts.length > 1 ? `Réception ${index + 1}` : 'Réception',
      at: receipt.receivedAt,
      dateOnly: false,
      detail: `${receipt.units} unité(s), ${receipt.lines} ligne(s)${invoice}${receipt.receivedBy ? ` · ${receipt.receivedBy}` : ''}`,
      state: 'done',
    });
  });
  if (order.status === 'RECEIVED') {
    steps.push({
      label: order.shortClosed ? 'Soldée' : 'Reçue en totalité',
      at: order.closedAt,
      dateOnly: false,
      detail: order.shortClosed
        ? `${order.orderedUnits - order.receivedUnits} unité(s) jamais livrée(s)`
        : null,
      state: 'done',
    });
  } else if (order.status === 'CANCELLED') {
    steps.push({
      label: 'Annulée',
      at: order.closedAt,
      dateOnly: false,
      detail: null,
      state: 'cancelled',
    });
  }
  return steps;
}

/** Share of the ordered units already received, 0–100. */
export function receivedPercent(ordered: number, received: number): number {
  return ordered <= 0 ? 0 : Math.min(Math.round((received / ordered) * 100), 100);
}

/** Server error detail (Problem Details) or a fallback sentence. */
export function errorDetail(error: unknown, fallback: string): string {
  return (error as { error?: { detail?: string } })?.error?.detail ?? fallback;
}
