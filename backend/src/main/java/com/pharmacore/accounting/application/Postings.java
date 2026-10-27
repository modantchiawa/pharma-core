package com.pharmacore.accounting.application;

import com.pharmacore.accounting.domain.Accounts;
import com.pharmacore.accounting.domain.EntryDraft;
import com.pharmacore.accounting.domain.Journal;
import com.pharmacore.accounting.domain.VatSplit;
import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.persistence.CashMovementEntity;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SaleLineEntity;
import com.pharmacore.sales.persistence.SalePaymentEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The accounting meaning of each business event, as pure functions over the entities. */
public final class Postings {

    private Postings() {
    }

    /**
     * Sale: money (or receivable) in, revenue and VAT out. If the amounts received differ from
     * the total (offline replay flagged for review), the gap goes to cash shortage/excess so the
     * entry always balances and the discrepancy stays visible.
     */
    public static EntryDraft sale(SaleEntity sale, LocalDate date) {
        return sale(sale, date, Map.of());
    }

    /**
     * @param insurerAccountsByPolicy receivable sub-account of the insurer covering each policy;
     *                                a third-party share on an unlisted policy lands on 4112
     */
    public static EntryDraft sale(SaleEntity sale, LocalDate date, Map<UUID, String> insurerAccountsByPolicy) {
        var entry = EntryDraft.builder(Journal.VT, date, "Vente " + sale.ticketNumber());
        long vat = 0;
        for (SaleLineEntity line : sale.lines()) {
            vat += VatSplit.ofInclusive(line.netCents(), line.vatRate()).vatCents();
        }
        long revenue = sale.totalNetCents() - vat;
        for (SalePaymentEntity payment : sale.payments()) {
            String account = payment.policyId() == null ? Accounts.settlementAccount(payment.method())
                    : insurerAccountsByPolicy.getOrDefault(payment.policyId(), Accounts.THIRD_PARTY_PAYERS);
            entry.debit(account, payment.amountCents(), paymentLabel(payment.method(), payment.provider()));
        }
        long received = sale.payments().stream().mapToLong(SalePaymentEntity::amountCents).sum();
        long gap = sale.totalNetCents() - received;
        if (gap > 0) {
            entry.debit(Accounts.CASH_SHORTAGE, gap, "Écart d'encaissement");
        } else if (gap < 0) {
            entry.credit(Accounts.CASH_EXCESS, -gap, "Excédent d'encaissement");
        }
        entry.credit(Accounts.SALES_DRUGS, revenue, "Ventes HT");
        entry.credit(Accounts.VAT_COLLECTED, vat, "TVA collectée");
        return entry.build();
    }

    public static EntryDraft customerPayment(long amountCents, PaymentMethod method, String provider,
                                             String customerName, LocalDate date) {
        return EntryDraft.builder(journalFor(method), date, "Règlement client " + customerName)
                .debit(Accounts.settlementAccount(method), amountCents, paymentLabel(method, provider))
                .credit(Accounts.CUSTOMERS, amountCents, "Créance client")
                .build();
    }

    /** Settlement of a statement by the insurer: money in, insurer receivable down. */
    public static EntryDraft insurerPayment(long amountCents, PaymentMethod method, String provider,
                                            String insurerName, String insurerAccount, String statementNumber,
                                            LocalDate date) {
        return EntryDraft.builder(journalFor(method), date, "Règlement " + insurerName + " bordereau " + statementNumber)
                .debit(Accounts.settlementAccount(method), amountCents, paymentLabel(method, provider))
                .credit(insurerAccount, amountCents, "Créance tiers payant " + insurerName)
                .build();
    }

    public static EntryDraft cashMovement(CashMovementEntity movement, LocalDate date) {
        return switch (movement.kind()) {
            case EXPENSE -> EntryDraft.builder(Journal.CA, date, "Dépense : " + movement.reason())
                    .debit(movement.expenseAccount() == null ? Accounts.CASH_SHORTAGE : movement.expenseAccount(),
                            movement.amountCents(), movement.reason())
                    .credit(Accounts.CASH, movement.amountCents(), "Sortie de caisse")
                    .build();
            case CASH_OUT -> EntryDraft.builder(Journal.CA, date, "Remise en banque : " + movement.reason())
                    .debit(Accounts.BANK, movement.amountCents(), movement.reason())
                    .credit(Accounts.CASH, movement.amountCents(), "Sortie de caisse")
                    .build();
            case CASH_IN -> EntryDraft.builder(Journal.CA, date, "Apport en caisse : " + movement.reason())
                    .debit(Accounts.CASH, movement.amountCents(), movement.reason())
                    .credit(Accounts.BANK, movement.amountCents(), "Retrait bancaire")
                    .build();
        };
    }

    /** One returned line: refunded amount (VAT included) and its VAT rate. */
    public record ReturnedLine(long refundCents, BigDecimal vatRate) {
    }

    /** Partial return refunded in cash: revenue and VAT go back, the cash leaves the drawer. */
    public static EntryDraft saleReturn(String ticketNumber, List<ReturnedLine> lines, LocalDate date) {
        var entry = EntryDraft.builder(Journal.VT, date, "Retour vente " + ticketNumber);
        long refund = 0;
        long vat = 0;
        for (ReturnedLine line : lines) {
            refund += line.refundCents();
            vat += VatSplit.ofInclusive(line.refundCents(), line.vatRate()).vatCents();
        }
        entry.debit(Accounts.SALES_DRUGS, refund - vat, "Retour ventes HT");
        entry.debit(Accounts.VAT_COLLECTED, vat, "TVA sur retour");
        entry.credit(Accounts.CASH, refund, "Remboursement en espèces");
        return entry.build();
    }

    /** Counted minus expected at closing: an excess is income, a shortage an expense. */
    public static EntryDraft sessionDifference(long differenceCents, String terminalId, LocalDate date) {
        if (differenceCents > 0) {
            return EntryDraft.builder(Journal.CA, date, "Excédent de caisse " + terminalId)
                    .debit(Accounts.CASH, differenceCents, "Excédent constaté")
                    .credit(Accounts.CASH_EXCESS, differenceCents, "Excédent de caisse")
                    .build();
        }
        return EntryDraft.builder(Journal.CA, date, "Manquant de caisse " + terminalId)
                .debit(Accounts.CASH_SHORTAGE, -differenceCents, "Manquant de caisse")
                .credit(Accounts.CASH, -differenceCents, "Manquant constaté")
                .build();
    }

    public static EntryDraft goodsReceipt(long exclVatCents, long vatCents, String supplierName, String invoiceNumber,
                                          LocalDate date) {
        return EntryDraft.builder(Journal.AC, date, "Achat " + supplierName
                        + (invoiceNumber == null ? "" : " facture " + invoiceNumber))
                .debit(Accounts.PURCHASES, exclVatCents, "Achats de marchandises")
                .debit(Accounts.VAT_DEDUCTIBLE, vatCents, "TVA déductible")
                .credit(Accounts.SUPPLIERS, exclVatCents + vatCents, supplierName)
                .build();
    }

    public static EntryDraft supplierPayment(long amountCents, PaymentMethod method, String supplierName,
                                             LocalDate date) {
        return EntryDraft.builder(journalFor(method), date, "Règlement fournisseur " + supplierName)
                .debit(Accounts.SUPPLIERS, amountCents, supplierName)
                .credit(Accounts.settlementAccount(method), amountCents, paymentLabel(method, null))
                .build();
    }

    private static Journal journalFor(PaymentMethod method) {
        return method == PaymentMethod.CASH ? Journal.CA : Journal.BQ;
    }

    private static String paymentLabel(PaymentMethod method, String provider) {
        return switch (method) {
            case CASH -> "Espèces";
            case CARD -> "Carte bancaire";
            case CHECK -> "Chèque";
            case MOBILE_MONEY -> provider == null ? "Mobile money" : "Mobile money " + provider;
            case THIRD_PARTY_PAYER -> "Tiers payant";
            case VOUCHER -> "Avoir";
            case CREDIT -> "Vente à crédit";
        };
    }
}
