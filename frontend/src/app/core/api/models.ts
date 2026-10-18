export type DrugListing = 'NONE' | 'LIST_I' | 'LIST_II' | 'STUPEFIANT';
export type PaymentMethod = 'CASH' | 'CARD' | 'CHECK' | 'THIRD_PARTY_PAYER' | 'VOUCHER';
export type SaleAnomaly =
  | 'PRICE_MISMATCH'
  | 'DISCOUNT_ABOVE_LIMIT'
  | 'MISSING_DISPENSING_RIGHT'
  | 'PRESCRIPTION_MISSING'
  | 'PAYMENT_MISMATCH'
  | 'INACTIVE_PRODUCT'
  | 'FUTURE_TIMESTAMP'
  | 'STOCK_SHORTFALL';

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
}
