package com.pharmacore.identity.web;

import com.pharmacore.identity.application.LoginSessionService;
import com.pharmacore.identity.application.SessionPolicyService;
import com.pharmacore.identity.domain.IdlePolicy;
import com.pharmacore.identity.domain.LockReason;
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

/**
 * Inactivity delays of the pharmacy's terminals, the lock of the signed-in session, its activity
 * pings and the journal of automatic logouts. A locked session reaches the lock and the journal
 * only (see {@code LockedRequestPolicy}); its ping is refused (423), so nothing wakes it up
 * without the PIN or password.
 */
@RestController
class SessionPolicyController {

    private final SessionPolicyService policies;
    private final LoginSessionService sessions;

    SessionPolicyController(SessionPolicyService policies, LoginSessionService sessions) {
        this.policies = policies;
        this.sessions = sessions;
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

    /** A lock reported as an event (terminal of an earlier version) locks the session too. */
    @PostMapping("/api/v1/me/session-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(@Valid @RequestBody EventRequest request) {
        switch (request.event()) {
            case IDLE_LOCK -> sessions.lockCurrent(LockReason.IDLE, request.terminalId(), request.idleMinutes());
            case MANUAL_LOCK -> sessions.lockCurrent(LockReason.MANUAL, request.terminalId(), request.idleMinutes());
            default -> policies.record(request.event(), request.terminalId(), request.idleMinutes());
        }
    }

    /** Padlock, Ctrl+L or the terminal's own inactivity timer: locked on the server at once. */
    @PostMapping("/api/v1/me/session/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void lock(@Valid @RequestBody LockRequest request) {
        // The terminal decides a manual or an idle lock; SERVER is the server's own
        LockReason reason = request.reason() == LockReason.MANUAL ? LockReason.MANUAL : LockReason.IDLE;
        sessions.lockCurrent(reason, request.terminalId(), request.idleMinutes());
    }

    /** The user works without calling the API (typing, scrolling): the filter has recorded it. */
    @PostMapping("/api/v1/me/session/activity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void activity() {
        // Nothing more to do: SessionLockFilter counted the request as activity, or refused it (423)
    }

    record PolicyRequest(@Min(0) @Max(IdlePolicy.MAX_LOCK_MINUTES) int lockAfterMinutes,
                         @Min(IdlePolicy.MIN_LOGOUT_MINUTES) @Max(IdlePolicy.MAX_LOGOUT_MINUTES) int logoutAfterMinutes) {
    }

    record LockRequest(@NotNull LockReason reason,
                       @NotBlank @Size(max = 40) String terminalId,
                       @Min(0) @Max(IdlePolicy.MAX_LOGOUT_MINUTES) int idleMinutes) {
    }

    record EventRequest(@NotNull SessionEvent event,
                        @NotBlank @Size(max = 40) String terminalId,
                        @Min(0) @Max(IdlePolicy.MAX_LOGOUT_MINUTES) int idleMinutes) {
    }
}
