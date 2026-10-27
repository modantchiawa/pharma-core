package com.pharmacore.identity.web;

import com.pharmacore.identity.application.SessionPolicyService;
import com.pharmacore.identity.domain.IdlePolicy;
import com.pharmacore.identity.domain.SessionEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Inactivity delays of the pharmacy's terminals and the journal of automatic locks and logouts. */
@RestController
class SessionPolicyController {

    private final SessionPolicyService policies;

    SessionPolicyController(SessionPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping("/api/v1/pharmacy/session-policy")
    IdlePolicy current() {
        return policies.current();
    }

    @PutMapping("/api/v1/pharmacy/session-policy")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    IdlePolicy update(@Valid @RequestBody PolicyRequest request) {
        return policies.update(request.lockAfterMinutes(), request.logoutAfterMinutes());
    }

    @PostMapping("/api/v1/me/session-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(@Valid @RequestBody EventRequest request) {
        policies.record(request.event(), request.terminalId(), request.idleMinutes());
    }

    record PolicyRequest(@Min(0) @Max(IdlePolicy.MAX_LOCK_MINUTES) int lockAfterMinutes,
                         @Min(IdlePolicy.MIN_LOGOUT_MINUTES) @Max(IdlePolicy.MAX_LOGOUT_MINUTES) int logoutAfterMinutes) {
    }

    record EventRequest(@NotNull SessionEvent event,
                        @NotBlank @Size(max = 40) String terminalId,
                        @Min(0) @Max(IdlePolicy.MAX_LOGOUT_MINUTES) int idleMinutes) {
    }
}
