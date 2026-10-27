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
  /** Where the product is stored in this pharmacy; null when it is not put away. */
  locationCode: string | null;
  locationName: string | null;
  locationKind: StorageLocationKind | null;
  locationDetail: string | null;
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
  /** Insurance policy covering a THIRD_PARTY_PAYER share. */
  policyId?: string;
}

export interface PrescriptionRequest {
  prescriberName: string;
  prescriberRpps?: string;
  patientReference?: string;
  /** Renewable prescription on file this dispensing counts against. */
  prescriptionId?: string;
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
  /** Absent on an open session for a cashier without CASH_APPROVE (blind count). */
  expectedCashCents?: number;
  countedCashCents?: number | null;
  differenceCents?: number | null;
  notes?: string | null;
  movements: {
    id: string;
    kind: string;
    amountCents: number;
    reason: string;
    occurredAt: string;
  }[];
  countedBreakdown: DenominationLine[];
  reconciliation: ReconciliationLine[];
  approvalStatus: 'NOT_REQUIRED' | 'PENDING' | 'APPROVED';
  approvedAt?: string | null;
  approvalNote?: string | null;
  reportHash?: string | null;
  previousReportHash?: string | null;
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
  lines: PurchaseOrderLine[];
  closedAt: string | null;
  /** Goods still awaited after the announced delivery day (pharmacy calendar). */
  overdue: boolean;
  /** Closed as received although some ordered units never came. */
  shortClosed: boolean;
  orderedUnits: number;
  receivedUnits: number;
  receivedCents: number;
  /** Value of the units still awaited (0 once closed). */
  remainingCents: number;
  supplier: SupplierContact;
}

export interface PurchaseOrderLine {
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
  /** Where the delivered boxes are put away; null when the product has no location. */
  locationCode: string | null;
  locationName: string | null;
  locationKind: StorageLocationKind | null;
  locationDetail: string | null;
}

export interface SupplierContact {
  contactName: string | null;
  phone: string | null;
  whatsapp: string | null;
  email: string | null;
  minOrderCents: number;
  leadTimeDays: number;
}

export type PurchaseOrderStatus = PurchaseOrder['status'];

export type PurchaseOrderScope = 'DRAFTS' | 'OPEN' | 'OVERDUE' | 'CLOSED' | 'ALL';

export interface PurchaseOrderSummary {
  /** Today in the pharmacy's calendar. */
  today: string;
  drafts: number;
  open: number;
  overdue: number;
  expectedToday: number;
  openAmountCents: number;
}

export interface PurchaseOrderGeneration {
  orders: PurchaseOrder[];
  created: number;
  updated: number;
  /** Proposal lines that no supplier sells yet. */
  unassigned: ProposalLine[];
}

export interface PurchaseOrderReceipt {
  id: string;
  receivedAt: string;
  supplierInvoiceNumber: string | null;
  invoiceDate: string | null;
  totalExclVatCents: number;
  vatCents: number;
  lines: number;
  units: number;
  receivedBy: string | null;
}

export interface GoodsReceiptRequest {
  supplierId: string;
  purchaseOrderId: string | null;
  supplierInvoiceNumber: string | null;
  invoiceDate: string | null;
  notes: string | null;
  lines: {
    productId: string;
    lotNumber: string;
    expiryDate: string;
    quantity: number;
    unitCostCents: number;
    vatRate: number | null;
    sellingPriceCents: number | null;
  }[];
}

export interface GoodsReceiptResult {
  id: string;
  totalExclVatCents: number;
  vatCents: number;
  lines: number;
  orderStatus: PurchaseOrderStatus | null;
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

export interface Dashboard {
  period: { from: string; to: string };
  sales: {
    count: number;
    netCents: number;
    averageBasketCents: number;
    marginCents: number;
    previousCount: number;
    previousNetCents: number;
    cancelledCount: number;
  };
  daily: { date: string; netCents: number; count: number }[];
  payments: { method: string; provider: string | null; amountCents: number }[];
  topProducts: {
    productId: string;
    cip13: string;
    designation: string;
    quantity: number;
    netCents: number;
  }[];
  stock: {
    valueAtCostCents: number;
    expiringWithin90DaysCents: number;
    expiringLots: number;
    belowThreshold: number;
    shortfallProducts: number;
  };
  receivables: { customerCreditCents: number; debtors: number };
  cash: { sessionsClosed: number; differenceCents: number; expensesCents: number };
}

// ---------------------------------------------------------------- annuaire public et réseau

export type PublicAvailabilityLevel = 'IN_STOCK' | 'LOW';

export interface PublicPharmacy {
  tenantId: string;
  slug: string;
  pharmacyName: string;
  address: string | null;
  city: string | null;
  phone: string | null;
  whatsapp: string | null;
  openingHours: string | null;
  latitude: number | null;
  longitude: number | null;
  sharesAvailability: boolean;
}

export interface PublicPharmacyAvailability {
  tenantId: string;
  pharmacyName: string;
  address: string | null;
  city: string | null;
  phone: string | null;
  whatsapp: string | null;
  latitude: number | null;
  longitude: number | null;
  level: PublicAvailabilityLevel;
}

export interface PublicProductAvailability {
  product: { id: string; cip13: string; designation: string; dci: string | null };
  pharmacies: PublicPharmacyAvailability[];
}

export interface PublicGuardSlot {
  slotId: string;
  networkName: string;
  type: 'NIGHT' | 'SUNDAY' | 'PUBLIC_HOLIDAY' | 'EXTENDED_DAY';
  startsAt: string;
  endsAt: string;
  tenantId: string;
  pharmacyName: string;
  address: string | null;
  city: string | null;
  phone: string | null;
  whatsapp: string | null;
  latitude: number | null;
  longitude: number | null;
  timeZone: string | null;
}

export interface PublicSharing {
  publicListing: boolean;
  publicAvailability: boolean;
  latitude: number | null;
  longitude: number | null;
  openingHours: string | null;
  whatsapp: string | null;
}

export interface NetworkSettings {
  networkId: string;
  name: string;
  region: string | null;
  coordinator: boolean;
  quantityCap: number;
  publishGuards: boolean;
}

export type NetworkAvailabilityLevel = 'OUT_OF_STOCK' | 'LOW' | 'AVAILABLE';

export interface PartnerAvailability {
  tenantId: string;
  pharmacyName: string;
  city: string | null;
  phone: string | null;
  networkId: string;
  networkName: string;
  level: NetworkAvailabilityLevel;
  quantity: number;
  capped: boolean;
}

export interface ProductPartners {
  productId: string;
  cip13: string;
  designation: string;
  dci: string | null;
  partners: PartnerAvailability[];
}

export interface PartnerProduct {
  productId: string;
  cip13: string;
  designation: string;
  dci: string | null;
  networkId: string;
  networkName: string;
  level: NetworkAvailabilityLevel;
  quantity: number;
  capped: boolean;
}

export interface GuardShareText {
  date: string;
  timeZone: string;
  slots: number;
  text: string;
}

// ---------------------------------------------------------------- fiabilité stock et caisse

export interface StockCountLine {
  id: string;
  productId: string;
  designation: string;
  cip13: string;
  ean13: string | null;
  lotNumber: string;
  expiryDate: string;
  /** Hidden from counters (blind count). */
  expectedQuantity?: number;
  countedQuantity?: number | null;
  variance?: number;
  countedAt?: string | null;
}

export interface StockCount {
  id: string;
  label: string;
  location?: string | null;
  status: 'OPEN' | 'VALIDATED' | 'CANCELLED';
  startedAt: string;
  validatedAt?: string | null;
  notes?: string | null;
  lineCount: number;
  countedLines: number;
  varianceUnits?: number;
  varianceValueCents?: number;
  lines: StockCountLine[];
}

export interface StockIntegrityReport {
  id: string;
  runAt: string;
  lotsChecked: number;
  mismatches: number;
  details: {
    kind: 'LOT_QUANTITY' | 'RESERVED_QUANTITY';
    designation: string;
    cip13: string;
    lotNumber: string | null;
    stored: number;
    computed: number;
  }[];
}

export interface ExpiryWriteOff {
  lotId: string;
  productId: string;
  designation: string;
  cip13: string;
  lotNumber: string;
  expiryDate: string;
  quantity: number;
  unitCostCents: number;
  valueCents: number;
  writtenOffAt: string;
}

export interface DenominationLine {
  valueCents: number;
  count: number;
}

export interface ReconciliationLine {
  method: PaymentMethod;
  provider: string | null;
  expectedCents: number;
  declaredCents: number;
  differenceCents: number;
}

export interface CashPolicy {
  cashDifferenceThresholdCents: number;
  cancelApprovalThresholdCents: number;
}

export interface PendingPayment {
  paymentId: string;
  saleId: string;
  ticketNumber: string;
  occurredAt: string;
  method: string;
  provider: string | null;
  reference: string | null;
  amountCents: number;
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'NOT_REQUIRED';
  rejectionReason: string | null;
}

export type StorageLocationKind =
  'SHELF' | 'DRAWER' | 'FRIDGE' | 'SAFE' | 'RESERVE' | 'COUNTER' | 'OTHER';

export interface StorageLocation {
  id: string;
  code: string;
  name: string;
  kind: StorageLocationKind;
  sortOrder: number;
  active: boolean;
  productCount: number;
  updatedAt: string;
}

/** Placement of a product as the stock lists return it. */
export interface StockLocation {
  id: string;
  code: string;
  name: string;
  kind: StorageLocationKind;
  detail: string | null;
}

// ---------------------------------------------------------------- tiers payant

export type InsurerKind = 'MUTUELLE' | 'ASSURANCE' | 'ETAT' | 'ENTREPRISE';

export interface Insurer {
  id: string;
  code: string;
  accountCode: string;
  active: boolean;
  details: {
    name: string;
    kind: InsurerKind;
    defaultCoverageRate: number;
    contactName: string | null;
    phone: string | null;
    email: string | null;
    address: string | null;
    paymentTermsDays: number;
  };
}

export interface InsurancePolicy {
  id: string;
  insurerId: string;
  insurerName: string;
  customerId: string;
  policyNumber: string;
  beneficiaryName: string;
  coverageRate: number;
  monthlyCeilingCents: number | null;
  /** Null when the policy has no monthly ceiling. */
  remainingCeilingCents: number | null;
  validFrom: string;
  validUntil: string | null;
  active: boolean;
  usable: boolean;
}

/** Insurer share of a sale total, as the server computes it (rate rounded half up, ceiling cap). */
export function insuredShareCents(policy: InsurancePolicy, netCents: number): number {
  const share = Math.floor((netCents * policy.coverageRate + 50) / 100);
  return policy.remainingCeilingCents === null
    ? share
    : Math.min(share, policy.remainingCeilingCents);
}

export type StatementStatus = 'DRAFT' | 'ISSUED' | 'PAID';

export interface InsurerStatement {
  id: string;
  insurerId: string;
  insurerName: string;
  number: string;
  periodFrom: string;
  periodTo: string;
  status: StatementStatus;
  totalCents: number;
  paidCents: number;
  remainingCents: number;
  lineCount: number;
  createdAt: string;
  issuedAt: string | null;
  paidAt: string | null;
  paymentMethod: string | null;
  paymentReference: string | null;
}

export interface InsurerStatementDetail {
  statement: InsurerStatement;
  lines: {
    saleId: string;
    ticketNumber: string;
    occurredAt: string;
    policyNumber: string | null;
    beneficiaryName: string | null;
    saleTotalCents: number;
    amountCents: number;
  }[];
}

export interface InsurerReceivable {
  insurerId: string;
  insurerName: string;
  accountCode: string;
  billedUnpaidCents: number;
  unbilledCents: number;
  unbilledSales: number;
  totalCents: number;
}

// ---------------------------------------------------------------- ordonnances

export type PrescriptionStatus = 'ACTIVE' | 'EXHAUSTED' | 'EXPIRED' | 'CANCELLED';

export interface Prescription {
  id: string;
  customerId: string | null;
  customerName: string | null;
  prescriberName: string;
  prescriberIdentifier: string | null;
  patientReference: string | null;
  issuedOn: string;
  validUntil: string;
  renewalsAllowed: number;
  renewalsUsed: number;
  renewalsRemaining: number;
  status: PrescriptionStatus;
  notes: string | null;
  lastDispensedAt: string | null;
  nextRenewalDue: string | null;
  createdAt: string;
  lines: {
    lineNo: number;
    productId: string;
    cip13: string | null;
    designation: string | null;
    listing: DrugListing | null;
    quantity: number;
    posology: string | null;
  }[];
}

export interface RegisterEntry {
  ordonnancierNumber: number | null;
  occurredAt: string;
  ticketNumber: string;
  saleId: string;
  prescriberName: string | null;
  prescriberIdentifier: string | null;
  patientReference: string | null;
  customerName: string | null;
  cip13: string;
  designation: string;
  listing: DrugListing;
  quantity: number;
  cashier: string;
  prescriptionId: string | null;
}

// ---------------------------------------------------------------- importation de données

export type ImportKind = 'CATALOG' | 'PRODUCTS' | 'STOCK' | 'CUSTOMERS' | 'SUPPLIERS';

export interface ImportFieldView {
  name: string;
  label: string;
  required: boolean;
  column: number | null;
}

export interface ImportKindView {
  kind: ImportKind;
  label: string;
  fields: ImportFieldView[];
}

export interface ImportPreview {
  format: string;
  charset: string;
  delimiter: string;
  headers: string[];
  rows: number;
  fields: ImportFieldView[];
  missingRequired: string[];
  sample: { line: number; values: Record<string, string>; error: string | null }[];
}

export interface ImportJob {
  id: string;
  kind: ImportKind;
  fileName: string;
  fileFormat: string;
  dryRun: boolean;
  status: string;
  total: number;
  created: number;
  updated: number;
  rejected: number;
  errors: { line: number; message: string }[];
  startedBy: string;
  startedAt: string;
  finishedAt: string;
}

// ---------------------------------------------------------------- impression

export type PaperFormat = 'MM58' | 'MM80' | 'A4';
export type PrinterMode = 'BROWSER' | 'ESCPOS_NETWORK' | 'ESCPOS_SHARED';

export interface PrinterSettings {
  terminalId: string;
  mode: PrinterMode;
  paper: PaperFormat;
  address: string | null;
  widthChars: 32 | 42 | 48;
  copies: number;
  cut: boolean;
  drawerKick: boolean;
  autoPrint: boolean;
  invoicePaper: 'A4' | 'NONE';
  configured: boolean;
}

export interface PrintResult {
  saleId: string | null;
  terminalId: string;
  mode: PrinterMode;
  copies: number;
  bytes: number;
}

export interface TerminalTicketRequest {
  ticketNumber: string;
  occurredAt: string;
  cashier: string;
  customer: {
    displayName: string;
    phone: string | null;
    address: string | null;
    policy: string | null;
  } | null;
  lines: {
    designation: string;
    quantity: number;
    unitPriceCents: number;
    discountCents: number;
    vatRate: number;
  }[];
  discountCents: number;
  netCents: number;
  payments: { label: string; amountCents: number }[];
  changeDueCents: number;
  vat: { rate: number; totalTtcCents: number; vatCents: number }[];
  offline: boolean;
}

// ---------------------------------------------------------------- supervision des postes et des comptes

/** Synchronization verdict of a cash register (server rules, thresholds in `SyncSupervision`). */
export type TerminalSyncState = 'SYNCED' | 'PENDING' | 'STALE' | 'NEVER';

export interface TerminalView {
  terminalId: string;
  appVersion: string;
  lastSeenAt: string;
  lastSyncAt: string | null;
  pendingSales: number;
  /** Sales refused by the server awaiting the pharmacist; null when the register does not report it. */
  rejectedSales: number | null;
  state: TerminalSyncState;
}

export interface TerminalCounts {
  total: number;
  synced: number;
  pending: number;
  stale: number;
  never: number;
  pendingSales: number;
}

export interface TerminalBoard {
  /** Instant the states are computed at: now, or the hub's last contact when relayed by a hub. */
  observedAt: string;
  relayedByHub: boolean;
  staleAfterSeconds: number;
  counts: TerminalCounts;
  terminals: TerminalView[];
}

export interface HubView {
  nodeId: string;
  appVersion: string | null;
  pairedAt: string;
  lastContactAt: string | null;
  lastPushAt: string | null;
  lastPullAt: string | null;
  reachable: boolean;
}

export interface SupervisedPharmacy {
  id: string;
  slug: string;
  legalName: string;
  displayName: string | null;
  city: string | null;
  status: string;
  topology: 'CLOUD' | 'HUB';
  subscriptionPlan: string;
  subscriptionExpiresAt: string | null;
  createdAt: string;
}

export interface SupervisedAccount {
  id: string;
  displayName: string;
  email: string;
  roles: { code: string; name: string }[];
  active: boolean;
  locked: boolean;
  mustChangePassword: boolean;
  pinSet: boolean;
  lastLoginAt: string | null;
}

export interface PharmacySupervision {
  pharmacy: SupervisedPharmacy;
  hub: HubView | null;
  terminals: TerminalBoard;
  accounts: SupervisedAccount[];
}

export interface PharmacySyncHealth {
  tenantId: string;
  topology: 'CLOUD' | 'HUB';
  terminals: number;
  outOfSync: number;
  stale: number;
  pendingSales: number;
  hubLastContactAt: string | null;
  hubReachable: boolean;
  observedAt: string;
}

// ---------------------------------------------------------------- abonnement et licence

export type LicenseStatusCode = 'ACTIVE' | 'WARNING' | 'GRACE' | 'BLOCKED';
export type LicenseReason =
  | 'PAYMENT_DUE'
  | 'OFFLINE_LIMIT'
  | 'EXPIRED'
  | 'SUSPENDED'
  | 'OFFLINE_TOO_LONG'
  | 'CLOCK_TAMPERED'
  | 'NO_LICENSE';
export type SubscriptionPaymentMethod =
  'MOBILE_MONEY' | 'VIREMENT' | 'ESPECES' | 'CHEQUE' | 'AUTRE';

export interface LicenseStateView {
  status: LicenseStatusCode;
  reason: LicenseReason | null;
  deadline: string | null;
  daysLeft: number | null;
  blocksAt: string | null;
  blockedSince: string | null;
}

export interface LicenseInfo {
  serial: number | null;
  issuedAt: string | null;
  validUntil: string | null;
  verifiedAt: string | null;
}

/** What every terminal polls, caches and enforces (banner, counter). */
export interface LicenseStatus {
  state: LicenseStateView;
  plan: string | null;
  paidUntil: string | null;
  graceUntil: string | null;
  license: LicenseInfo;
  mode: 'HUB' | 'CLOUD';
  serverTime: string;
}

export interface SubscriptionPlan {
  code: string;
  name: string;
  monthlyPriceXof: number;
  maxTerminals: number;
  description: string | null;
  active: boolean;
}

export interface SubscriptionPayment {
  id: string;
  planCode: string;
  amountXof: number;
  method: SubscriptionPaymentMethod;
  reference: string | null;
  months: number;
  periodFrom: string;
  periodTo: string;
  paidAt: string;
  recordedBy: string;
  recordedAt: string;
  note: string | null;
}

export interface PharmacySubscription {
  status: LicenseStatus;
  plan: SubscriptionPlan | null;
  payments: SubscriptionPayment[];
  vendor: { name: string | null; email: string | null; phone: string | null };
}

export interface TenantSubscription {
  tenantId: string;
  slug: string;
  legalName: string;
  tenantStatus: 'PROVISIONING' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED';
  topology: 'HUB' | 'CLOUD';
  plan: string;
  planName: string | null;
  monthlyPriceXof: number;
  paidUntil: string | null;
  graceUntil: string | null;
  suspendedAt: string | null;
  suspensionReason: string | null;
  lastPaymentOn: string | null;
  lastPaymentXof: number | null;
  license: LicenseInfo;
  lastHubContactAt: string | null;
  state: LicenseStateView;
}

export interface TenantSubscriptionDetail {
  subscription: TenantSubscription;
  payments: SubscriptionPayment[];
  hubs: {
    nodeId: string;
    appVersion: string | null;
    pairedAt: string | null;
    lastSeenAt: string | null;
    lastPullAt: string | null;
  }[];
}
