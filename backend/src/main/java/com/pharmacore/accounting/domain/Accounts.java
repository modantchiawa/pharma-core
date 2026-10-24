package com.pharmacore.accounting.domain;

import com.pharmacore.sales.domain.PaymentMethod;

/** Account codes of the minimal chart seeded for every pharmacy (see migration V6). */
public final class Accounts {

    public static final String CAPITAL = "101";
    public static final String RESULT = "130";
    public static final String STOCK = "311";
    public static final String SUPPLIERS = "401";
    public static final String CUSTOMERS = "4111";
    public static final String THIRD_PARTY_PAYERS = "4112";
    public static final String VAT_COLLECTED = "4431";
    public static final String VAT_DEDUCTIBLE = "4452";
    public static final String BANK = "521";
    public static final String MOBILE_MONEY = "5221";
    public static final String CASH = "571";
    public static final String CHEQUES_AND_CARDS = "5711";
    public static final String PURCHASES = "601";
    public static final String STOCK_VARIATION = "6031";
    public static final String CASH_SHORTAGE = "658";
    public static final String SALES_DRUGS = "7011";
    public static final String SALES_PARAPHARMACY = "7012";
    public static final String CASH_EXCESS = "758";

    private Accounts() {
    }

    /** Where money received through a payment method lands. */
    public static String settlementAccount(PaymentMethod method) {
        return switch (method) {
            case CASH -> CASH;
            case CARD, CHECK -> CHEQUES_AND_CARDS;
            case MOBILE_MONEY -> MOBILE_MONEY;
            case THIRD_PARTY_PAYER -> THIRD_PARTY_PAYERS;
            case CREDIT, VOUCHER -> CUSTOMERS;
        };
    }

    public static boolean isRevenue(String code) {
        return code.startsWith("7");
    }

    public static boolean isExpense(String code) {
        return code.startsWith("6");
    }
}
