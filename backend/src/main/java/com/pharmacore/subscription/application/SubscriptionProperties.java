package com.pharmacore.subscription.application;

import com.pharmacore.subscription.domain.LicensePolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param vendorName     who to contact for a renewal, shown on the pharmacy's subscription page
 * @param paymentWarning warning before the end of the paid period (7 days)
 * @param grace          grace after the end of the paid period (3 days)
 * @param offlineWindow  how long a hub trusts its last license (15 days)
 * @param offlineWarning warning before the offline limit (5 days)
 * @param clockTolerance how far back a hub's clock may go before it is considered tampered with
 */
@ConfigurationProperties("pharmacore.subscription")
public record SubscriptionProperties(String vendorName,
                                     String vendorEmail,
                                     String vendorPhone,
                                     Duration paymentWarning,
                                     Duration grace,
                                     Duration offlineWindow,
                                     Duration offlineWarning,
                                     Duration clockTolerance) {

    public SubscriptionProperties {
        LicensePolicy defaults = LicensePolicy.DEFAULT;
        vendorName = blankToNull(vendorName);
        vendorEmail = blankToNull(vendorEmail);
        vendorPhone = blankToNull(vendorPhone);
        paymentWarning = paymentWarning == null ? defaults.paymentWarning() : paymentWarning;
        grace = grace == null ? defaults.grace() : grace;
        offlineWindow = offlineWindow == null ? defaults.offlineWindow() : offlineWindow;
        offlineWarning = offlineWarning == null ? defaults.offlineWarning() : offlineWarning;
        clockTolerance = clockTolerance == null ? defaults.clockTolerance() : clockTolerance;
    }

    public LicensePolicy policy() {
        return new LicensePolicy(paymentWarning, grace, offlineWindow, offlineWarning, clockTolerance);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
