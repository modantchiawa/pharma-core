package com.pharmacore.safety.application;

import com.pharmacore.safety.domain.SafetyRule;
import com.pharmacore.safety.domain.Severity;
import com.pharmacore.safety.domain.SubstanceNames;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The interaction referential ({@code drug_interaction}), owned by the platform and replicated to
 * every hub. Rows carry deterministic identifiers derived from their natural key, so the seed
 * migration produces the same rows on the hub and on the cloud.
 */
@Component
public class SafetyRuleStore {

    public record RuleRow(UUID id, SafetyRule rule, String source, boolean active, Instant updatedAt) {
    }

    public record Page(List<RuleRow> items, Instant nextSince, UUID nextAfter, boolean hasMore) {
    }

    private final JdbcClient jdbc;

    public SafetyRuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Active rules that may concern the given substance tokens. Every ATC-class rule is returned
     * (there are few, and the class match is done in memory); name rules are filtered on their
     * exact token, so callers pass every word prefix of every component.
     */
    @Transactional(readOnly = true)
    public List<SafetyRule> matching(Collection<String> tokens) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String token : tokens) {
            String normalized = SubstanceNames.normalize(token);
            if (!normalized.isEmpty()) {
                wanted.add(normalized);
            }
        }
        return jdbc.sql("""
                        SELECT kind, substance, counterpart, severity, advice
                        FROM drug_interaction
                        WHERE active
                          AND (substance = ANY(:tokens) OR counterpart = ANY(:tokens)
                               OR substance LIKE 'ATC:%' OR counterpart LIKE 'ATC:%')
                        ORDER BY severity, substance, counterpart""")
                .param("tokens", wanted.toArray(String[]::new))
                .query((rs, rowNum) -> new SafetyRule(SafetyRule.Kind.valueOf(rs.getString("kind")),
                        rs.getString("substance"), rs.getString("counterpart"),
                        Severity.valueOf(rs.getString("severity")), rs.getString("advice")))
                .list();
    }

    /** Incremental feed for the terminals' offline copy, inactive rows included so they can be dropped. */
    @Transactional(readOnly = true)
    public Page changes(Instant since, UUID after, int limit) {
        int pageSize = Math.clamp(limit, 1, 2_000);
        List<RuleRow> rows = jdbc.sql("""
                        SELECT id, kind, substance, counterpart, severity, advice, source, active, updated_at
                        FROM drug_interaction
                        WHERE updated_at > :since
                           OR (updated_at = :since AND (:after::uuid IS NULL OR id > :after::uuid))
                        ORDER BY updated_at, id
                        LIMIT :limit""")
                .param("since", Timestamp.from(since))
                .param("after", after)
                .param("limit", pageSize + 1)
                .query((rs, rowNum) -> new RuleRow(rs.getObject("id", UUID.class),
                        new SafetyRule(SafetyRule.Kind.valueOf(rs.getString("kind")), rs.getString("substance"),
                                rs.getString("counterpart"), Severity.valueOf(rs.getString("severity")),
                                rs.getString("advice")),
                        rs.getString("source"), rs.getBoolean("active"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
        boolean hasMore = rows.size() > pageSize;
        List<RuleRow> items = hasMore ? rows.subList(0, pageSize) : rows;
        RuleRow last = items.isEmpty() ? null : items.getLast();
        return new Page(items, last == null ? since : last.updatedAt(), last == null ? after : last.id(), hasMore);
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return jdbc.sql("SELECT count(*) FROM drug_interaction WHERE active").query(Long.class).single();
    }

    /** Creates or refreshes rules by natural key; returns how many rows were written. */
    @Transactional
    public int upsert(List<SafetyRule> rules, String source) {
        int written = 0;
        for (SafetyRule rule : rules) {
            written += jdbc.sql("""
                            INSERT INTO drug_interaction (id, kind, substance, counterpart, severity, advice, source, active,
                                                          updated_at)
                            VALUES (md5(:kind || :substance || '|' || :counterpart)::uuid, :kind, :substance, :counterpart,
                                    :severity, :advice, :source, true, now())
                            ON CONFLICT (kind, substance, counterpart) DO UPDATE
                                SET severity = EXCLUDED.severity, advice = EXCLUDED.advice, source = EXCLUDED.source,
                                    active = true, updated_at = now(), version = drug_interaction.version + 1""")
                    .param("kind", rule.kind().name())
                    .param("substance", rule.substance())
                    .param("counterpart", rule.counterpart())
                    .param("severity", rule.severity().name())
                    .param("advice", rule.advice())
                    .param("source", source)
                    .update();
        }
        return written;
    }

    /** @return true when a rule was found and deactivated */
    @Transactional
    public boolean deactivate(SafetyRule.Kind kind, String substance, String counterpart) {
        SafetyRule key = new SafetyRule(kind, substance, counterpart, Severity.PRECAUTION, "");
        return jdbc.sql("""
                        UPDATE drug_interaction SET active = false, updated_at = now(), version = version + 1
                        WHERE kind = :kind AND substance = :substance AND counterpart = :counterpart AND active""")
                .param("kind", kind.name())
                .param("substance", key.substance())
                .param("counterpart", key.counterpart())
                .update() > 0;
    }
}
