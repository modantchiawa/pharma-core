package com.pharmacore.control.application;

import java.util.UUID;

/** An alert was raised; pushed to the pharmacy's screens once the transaction commits. */
public record ControlAlertRaised(UUID tenantId, AlertService.AlertView alert) {
}
