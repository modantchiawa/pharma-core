package com.pharmacore.safety.application;

import com.pharmacore.customers.persistence.CustomerRepository;
import com.pharmacore.safety.domain.AllergyFamily;
import com.pharmacore.safety.domain.HealthContext;
import com.pharmacore.safety.domain.PatientProfile;
import com.pharmacore.safety.domain.SubstanceNames;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The patient's file kept by the pharmacy: what the counter needs to warn about a product. It
 * never leaves the pharmacy (not exposed to the network, the public directory, the messages or
 * the owner's alerts).
 */
@Service
public class HealthProfileService {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    public record ProfileView(UUID customerId, LocalDate birthDate, Integer ageYears, String sex, Integer weightKg,
                              boolean pregnant, boolean breastfeeding, List<String> allergies,
                              List<HealthContext> conditions, String notes, Instant updatedAt, boolean empty) {
    }

    public record Command(LocalDate birthDate, String sex, Integer weightKg, boolean pregnant, boolean breastfeeding,
                          List<String> allergies, List<HealthContext> conditions, String notes) {
    }

    private final CustomerRepository customers;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public HealthProfileService(CustomerRepository customers, AuditTrail audit, JdbcClient jdbc, ObjectMapper json,
                                Clock clock) {
        this.customers = customers;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProfileView get(UUID customerId, LocalDate today) {
        customers.findById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        return load(customerId).map(row -> row.view(today))
                .orElse(new ProfileView(customerId, null, null, null, null, false, false, List.of(), List.of(), null,
                        null, true));
    }

    /** What the rules need; empty for an unknown customer or a customer without a file. */
    @Transactional(readOnly = true)
    public PatientProfile profileOf(UUID customerId) {
        if (customerId == null) {
            return PatientProfile.EMPTY;
        }
        return load(customerId).map(Row::profile).orElse(PatientProfile.EMPTY);
    }

    @Transactional
    public ProfileView update(UUID customerId, Command command, LocalDate today) {
        customers.findById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        if (command.birthDate() != null && command.birthDate().isAfter(today)) {
            throw new BusinessRuleException("invalid-birth-date", "La date de naissance est dans le futur");
        }
        if (command.pregnant() && "M".equals(command.sex())) {
            throw new BusinessRuleException("invalid-profile", "Grossesse incompatible avec le sexe masculin");
        }
        List<String> allergies = cleanAllergies(command.allergies());
        Set<HealthContext> conditions = command.conditions() == null ? EnumSet.noneOf(HealthContext.class)
                : EnumSet.copyOf(command.conditions());
        conditions.removeIf(context -> !context.condition());
        var user = TenantContext.requireTenantUser();
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO customer_health_profile (customer_id, tenant_id, birth_date, sex, weight_kg, pregnant,
                                                             breastfeeding, allergies, conditions, notes, updated_by, updated_at)
                        VALUES (:customer, :tenant, :birth::date, :sex, :weight, :pregnant, :breastfeeding,
                                :allergies::jsonb, :conditions::jsonb, :notes, :by, :at)
                        ON CONFLICT (customer_id) DO UPDATE
                            SET birth_date = EXCLUDED.birth_date, sex = EXCLUDED.sex, weight_kg = EXCLUDED.weight_kg,
                                pregnant = EXCLUDED.pregnant, breastfeeding = EXCLUDED.breastfeeding,
                                allergies = EXCLUDED.allergies, conditions = EXCLUDED.conditions, notes = EXCLUDED.notes,
                                updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at,
                                version = customer_health_profile.version + 1""")
                .param("customer", customerId)
                .param("tenant", user.tenant().value())
                .param("birth", command.birthDate() == null ? null : Date.valueOf(command.birthDate()))
                .param("sex", command.sex())
                .param("weight", command.weightKg())
                .param("pregnant", command.pregnant())
                .param("breastfeeding", command.breastfeeding())
                .param("allergies", json.writeValueAsString(allergies))
                .param("conditions", json.writeValueAsString(conditions.stream().map(Enum::name).toList()))
                .param("notes", command.notes() == null || command.notes().isBlank() ? null : command.notes().trim())
                .param("by", user.userId())
                .param("at", Timestamp.from(now))
                .update();
        audit.record("CUSTOMER_HEALTH_PROFILE_UPDATED", "CUSTOMER", customerId, Map.of(
                "allergies", Integer.toString(allergies.size()),
                "conditions", Integer.toString(conditions.size()),
                "pregnant", Boolean.toString(command.pregnant())));
        return get(customerId, today);
    }

    /** Family codes are kept as such; anything else is a substance name, normalised and de-duplicated. */
    static List<String> cleanAllergies(List<String> declared) {
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : declared == null ? List.<String>of() : declared) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Optional<AllergyFamily> family = AllergyFamily.parse(value);
            if (family.isPresent()) {
                cleaned.add(family.get().name());
            } else {
                String normalized = SubstanceNames.normalize(value);
                if (normalized.length() >= 3 && normalized.length() <= 80) {
                    cleaned.add(normalized);
                }
            }
            if (cleaned.size() >= 30) {
                break;
            }
        }
        return new ArrayList<>(cleaned);
    }

    private record Row(UUID customerId, LocalDate birthDate, String sex, Integer weightKg, boolean pregnant,
                       boolean breastfeeding, List<String> allergies, Set<HealthContext> conditions, String notes,
                       Instant updatedAt) {

        PatientProfile profile() {
            return new PatientProfile(birthDate, pregnant, breastfeeding, allergies, conditions);
        }

        ProfileView view(LocalDate today) {
            PatientProfile profile = profile();
            return new ProfileView(customerId, birthDate, profile.ageYears(today), sex, weightKg, pregnant,
                    breastfeeding, allergies, List.copyOf(conditions), notes, updatedAt, profile.isEmpty());
        }
    }

    private Optional<Row> load(UUID customerId) {
        return jdbc.sql("""
                        SELECT customer_id, birth_date, sex, weight_kg, pregnant, breastfeeding, allergies::text AS allergies,
                               conditions::text AS conditions, notes, updated_at
                        FROM customer_health_profile WHERE customer_id = :customer""")
                .param("customer", customerId)
                .query((rs, rowNum) -> {
                    Date birth = rs.getDate("birth_date");
                    Integer weight = rs.getObject("weight_kg") == null ? null : rs.getInt("weight_kg");
                    Set<HealthContext> conditions = EnumSet.noneOf(HealthContext.class);
                    for (String code : json.readValue(rs.getString("conditions"), STRINGS)) {
                        conditions.add(HealthContext.valueOf(code));
                    }
                    return new Row(rs.getObject("customer_id", UUID.class),
                            birth == null ? null : birth.toLocalDate(), rs.getString("sex"), weight,
                            rs.getBoolean("pregnant"), rs.getBoolean("breastfeeding"),
                            json.readValue(rs.getString("allergies"), STRINGS), conditions, rs.getString("notes"),
                            rs.getTimestamp("updated_at").toInstant());
                })
                .optional();
    }
}
