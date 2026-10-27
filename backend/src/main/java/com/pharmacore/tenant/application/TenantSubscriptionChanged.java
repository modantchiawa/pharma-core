package com.pharmacore.tenant.application;

import java.util.UUID;

/**
 * Published after a committed change of what a pharmacy's license says (creation, status, plan,
 * paid period), so the license is re-issued and cached states are dropped.
 */
public record TenantSubscriptionChanged(UUID tenantId) {
}
