package com.pharmacore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class AuditTrailIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void everySensitiveActionIsChainedAndTheChainVerifies() throws Exception {
        var pharmacy = onboardPharmacy("Audit");
        var product = createProduct("EFFERALGAN 500MG", "NONE", 195);
        receiveLot(pharmacy, product, "E-1", "2028-03-31", 8);

        call(get("/api/v1/audit/events"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("STOCK_RECEIVED"))
                .andExpect(jsonPath("$[?(@.action == 'TENANT_PROVISIONED')]").exists());
        call(get("/api/v1/audit/verification"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void auditRowsCannotBeAlteredByTheApplicationRole() throws Exception {
        var pharmacy = onboardPharmacy("Immutable");
        var context = RequestContext.of(Actor.SystemProcess.forTenant("tamper-attempt", new TenantId(pharmacy.tenantId())));

        assertThatThrownBy(() -> TenantContext.run(context, () -> tx.executeWithoutResult(status ->
                jdbc.sql("UPDATE audit_event SET details = '{}'").update())))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> TenantContext.run(context, () -> tx.executeWithoutResult(status ->
                jdbc.sql("DELETE FROM audit_event").update())))
                .rootCause()
                .hasMessageContaining("permission denied");

        long events = TenantContext.call(context, () -> tx.execute(status ->
                jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()));
        // USER_REGISTERED + TENANT_PROVISIONED + LOGIN; registration/activation belong to the platform chain
        assertThat(events).isEqualTo(3);
    }
}
