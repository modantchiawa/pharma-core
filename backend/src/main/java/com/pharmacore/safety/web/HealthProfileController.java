package com.pharmacore.safety.web;

import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.safety.application.HealthProfileService;
import com.pharmacore.safety.application.HealthProfileService.ProfileView;
import com.pharmacore.safety.domain.AllergyFamily;
import com.pharmacore.safety.domain.HealthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The patient's file of a customer: what the counter checks products against. */
@RestController
@RequestMapping("/api/v1/customers")
class HealthProfileController {

    private final HealthProfileService profiles;
    private final TenantCalendar calendar;

    HealthProfileController(HealthProfileService profiles, TenantCalendar calendar) {
        this.profiles = profiles;
        this.calendar = calendar;
    }

    record Choice(String code, String label) {
    }

    /** Pick lists for the form: allergy families and chronic conditions. */
    record Vocabulary(List<Choice> allergyFamilies, List<Choice> conditions) {
    }

    @GetMapping("/health-vocabulary")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'CUSTOMER_MANAGE')")
    Vocabulary vocabulary() {
        return new Vocabulary(
                Arrays.stream(AllergyFamily.values()).map(f -> new Choice(f.name(), f.label())).toList(),
                Arrays.stream(HealthContext.values()).filter(HealthContext::condition)
                        .map(c -> new Choice(c.name(), c.label())).toList());
    }

    @GetMapping("/{customerId}/health")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'CUSTOMER_MANAGE')")
    ProfileView get(@PathVariable UUID customerId) {
        return profiles.get(customerId, calendar.today());
    }

    record ProfileRequest(LocalDate birthDate,
                          @Pattern(regexp = "^[FM]$") String sex,
                          @Min(1) @Max(300) Integer weightKg,
                          boolean pregnant,
                          boolean breastfeeding,
                          @Size(max = 30) List<@Size(max = 80) String> allergies,
                          @Size(max = 20) List<HealthContext> conditions,
                          @Size(max = 500) String notes) {
    }

    @PutMapping("/{customerId}/health")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_MANAGE', 'PRESCRIPTION_DISPENSE')")
    ProfileView update(@PathVariable UUID customerId, @Valid @RequestBody ProfileRequest request) {
        return profiles.update(customerId, new HealthProfileService.Command(request.birthDate(), request.sex(),
                request.weightKg(), request.pregnant(), request.breastfeeding(), request.allergies(),
                request.conditions(), request.notes()), calendar.today());
    }
}
