export type DrugListing = 'NONE' | 'LIST_I' | 'LIST_II' | 'STUPEFIANT';
export type PaymentMethod =
  'CASH' | 'CARD' | 'CHECK' | 'THIRD_PARTY_PAYER' | 'VOUCHER' | 'MOBILE_MONEY' | 'CREDIT';

export const MOBILE_MONEY_PROVIDERS = [
  'AIRTEL_MONEY',
  'MOOV_MONEY',
  'ORANGE_MONEY',
  'ZAMANI_CASH',
  'MYNITA',
  'WAVE',
] as const;
export type SaleAnomaly =
  | 'PRICE_MISMATCH'
  | 'DISCOUNT_ABOVE_LIMIT'
  | 'MISSING_DISPENSING_RIGHT'
  | 'PRESCRIPTION_MISSING'
  | 'PAYMENT_MISMATCH'
  | 'INACTIVE_PRODUCT'
  | 'FUTURE_TIMESTAMP'
  | 'STOCK_SHORTFALL'
  | 'CREDIT_NOT_ALLOWED'
  | 'CREDIT_LIMIT_EXCEEDED';

export interface Me {
  userId: string;
  tenantId: string;
  displayName: string;
  roles: string[];
  permissions: string[];
  maxDiscountPercent: number;
}

export interface PosCatalogItem {
  productId: string;
  cip13: string;
  ean13: string | null;
  designation: string;
  dci: string | null;
  listing: DrugListing;
  vatRate: number;
  unitPriceCents: number | null;
  availableQuantity: number;
  active: boolean;
  changedAt: string;
}

export interface PosCatalogPage {
  items: PosCatalogItem[];
  nextSince: string;
  nextAfter: string | null;
  hasMore: boolean;
}

export interface SaleLineRequest {
  productId: string;
  quantity: number;
  unitPriceCents: number;
  discountCents: number;
}

export interface SalePaymentRequest {
  method: PaymentMethod;
  amountCents: number;
  thirdPartyCode?: string;
  reference?: string;
  provider?: string;
}

export interface PrescriptionRequest {
  prescriberName: string;
  prescriberRpps?: string;
  patientReference?: string;
}

export interface SaleRequest {
  clientSaleId: string;
  terminalId: string;
  ticketNumber: string;
  occurredAt: string;
  cashierUserId: string;
  customerId?: string;
  lines: SaleLineRequest[];
  payments: SalePaymentRequest[];
  prescription?: PrescriptionRequest;
}

export type SaleOutcome =
  | {
      type: 'ACCEPTED';
      clientSaleId: string;
      saleId: string;
      ordonnancierNumber?: number;
      anomalies: SaleAnomaly[];
    }
  | { type: 'DUPLICATE'; clientSaleId: string; saleId: string }
  | {
      type: 'REJECTED';
      clientSaleId: string;
      code: string;
      violations: SaleAnomaly[];
      message: string;
    }
  | { type: 'RETRY'; clientSaleId: string; message: string };

export interface SyncResponse {
  serverTime: string;
  outcomes: SaleOutcome[];
}

export interface Branding {
  displayName: string;
  addressLine1: string | null;
  addressLine2: string | null;
  postalCode: string | null;
  city: string | null;
  countryCode: string;
  phone: string | null;
  email: string | null;
  legalIdentifier: string | null;
  finessNumber: string | null;
  ordonnancierPrefix: string | null;
  primaryColor: string;
  secondaryColor: string;
  accentColor: string;
  receiptHeader: string | null;
  receiptFooter: string | null;
}

export interface BrandingView {
  branding: Branding;
  hasLogo: boolean;
  version: number;
  currency: string;
}

export interface Customer {
  id: string;
  code: string;
  displayName: string;
  phone: string | null;
  email: string | null;
  address: string | null;
  creditLimitCents: number;
  creditBalanceCents: number;
  availableCreditCents: number;
  loyaltyPoints: number;
  notes: string | null;
  active: boolean;
}

export interface CashSessionReport {
  sessionId: string;
  terminalId: string;
  status: 'OPEN' | 'CLOSED';
  openedAt: string;
  closedAt: string | null;
  openingFloatCents: number;
  salesCount: number;
  cancelledCount: number;
  salesNetCents: number;
  byPaymentMethod: Partial<Record<PaymentMethod, number>>;
  mobileMoneyByProvider: Record<string, number>;
  customerPaymentsCashCents: number;
  cashInCents: number;
  cashOutCents: number;
  expensesCents: number;
  expectedCashCents: number;
  countedCashCents: number | null;
  differenceCents: number | null;
  notes: string | null;
  movements: {
    id: string;
    kind: string;
    amountCents: number;
    reason: string;
    occurredAt: string;
  }[];
}

export interface ProposalLine {
  productId: string;
  cip13: string;
  designation: string;
  supplierId: string | null;
  supplierName: string | null;
  available: number;
  onOrder: number;
  expiringSoon: number;
  soldLastPeriod: number;
  shortfall: number;
  dailyDemand: number;
  targetStock: number;
  suggestedQuantity: number;
  packSize: number;
  purchasePriceCents: number | null;
  reasons: string[];
}

export interface ProposalSheet {
  date: string;
  periodDays: number;
  lines: ProposalLine[];
  estimatedTotalCents: number;
}

export interface Supplier {
  id: string;
  code: string;
  active: boolean;
  details: {
    name: string;
    contactName: string | null;
    phone: string | null;
    whatsapp: string | null;
    email: string | null;
    address: string | null;
    leadTimeDays: number;
    orderDays: string | null;
    minOrderCents: number;
    paymentTerms: string | null;
    notes: string | null;
  };
}

export interface PurchaseOrder {
  id: string;
  number: string;
  supplierId: string;
  supplierName: string;
  status: 'DRAFT' | 'SENT' | 'PARTIALLY_RECEIVED' | 'RECEIVED' | 'CANCELLED';
  createdAt: string;
  sentAt: string | null;
  expectedAt: string | null;
  totalCents: number;
  notes: string | null;
  lines: {
    lineNo: number;
    productId: string;
    designation: string;
    cip13: string;
    quantityOrdered: number;
    quantityReceived: number;
    unitCostCents: number;
    packSize: number;
    suggestedQuantity: number | null;
    reason: string | null;
  }[];
}

export interface IncomeStatement {
  from: string;
  to: string;
  salesCents: number;
  purchasesCents: number;
  openingStockCents: number;
  closingStockCents: number;
  costOfGoodsSoldCents: number;
  grossMarginCents: number;
  otherExpensesCents: number;
  otherIncomeCents: number;
  resultCents: number;
  expensesByAccount: Record<string, number>;
}

export interface BalanceSheet {
  asOf: string;
  stockCents: number;
  customerReceivablesCents: number;
  thirdPartyReceivablesCents: number;
  cashCents: number;
  bankCents: number;
  mobileMoneyCents: number;
  chequesCardsCents: number;
  vatDeductibleCents: number;
  totalAssetsCents: number;
  suppliersCents: number;
  vatCollectedCents: number;
  capitalCents: number;
  cumulativeResultCents: number;
  totalLiabilitiesCents: number;
}

export interface BalanceRow {
  account: string;
  label: string;
  kind: string;
  debitCents: number;
  creditCents: number;
  balanceCents: number;
}
