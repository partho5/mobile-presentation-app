package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.entitlements.Catalogue;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/** A plan's monthly price as text: "$19 / month". */
final class PlanPrice {

    private PlanPrice() {
    }

    /** "$19 / month", or null for the Free plan, an unknown plan or a price of zero. */
    static String monthly(Context c, Catalogue.Plan plan) {
        if (plan == null || plan.monthlyPriceCents <= 0) return null;
        return c.getString(R.string.plans_price_month,
                amount(plan.monthlyPriceCents, plan.currency, Locale.getDefault()));
    }

    /** Whole amounts without decimals ("$19"), others with two ("$9.50"); an unknown currency code is shown as it is. */
    static String amount(long cents, String currencyCode, Locale locale) {
        boolean whole = cents % 100 == 0;
        try {
            NumberFormat nf = NumberFormat.getCurrencyInstance(locale);
            nf.setCurrency(Currency.getInstance(currencyCode == null ? "USD" : currencyCode));
            nf.setMinimumFractionDigits(whole ? 0 : 2);
            nf.setMaximumFractionDigits(whole ? 0 : 2);
            return nf.format(cents / 100.0);
        } catch (IllegalArgumentException e) {
            String number = whole ? String.valueOf(cents / 100)
                    : String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
            return currencyCode + " " + number;
        }
    }
}
