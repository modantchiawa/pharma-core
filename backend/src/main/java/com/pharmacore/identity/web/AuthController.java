package com.pharmacore.identity.web;

import com.pharmacore.identity.application.LocalAuthService;
import com.pharmacore.identity.application.LocalAuthService.Tokens;
import com.pharmacore.identity.application.LocalAuthService.UnlockMethod;
import com.pharmacore.identity.application.SessionClosedException;
import com.pharmacore.identity.domain.IdlePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Offline-capable authentication endpoints (hub and cloud). */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final LocalAuthService auth;

    AuthController(LocalAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return TokenResponse.of(auth.login(request.pharmacy(), request.email(), request.password(), request.terminalId()));
    }

    @PostMapping("/pin")
    TokenResponse pin(@Valid @RequestBody PinLoginRequest request) {
        return TokenResponse.of(auth.pinLogin(request.terminalToken(), request.email(), request.pin(), request.terminalId()));
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(auth.refresh(request.refreshToken()));
    }

    /** The lock screen: the server checks the PIN or password of the session's owner. */
    @PostMapping("/unlock")
    TokenResponse unlock(@Valid @RequestBody UnlockRequest request) {
        return TokenResponse.of(auth.unlock(request.refreshToken(), request.method(), request.secret()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
    }

    record LoginRequest(@Size(max = 63) String pharmacy,
                        @NotBlank @Size(max = 200) String email,
                        @NotBlank @Size(max = 200) String password,
                        @NotBlank @Size(max = 40) String terminalId) {
    }

    record PinLoginRequest(@NotBlank String terminalToken,
                           @NotBlank @Size(max = 200) String email,
                           @NotBlank @Size(max = 6) String pin,
                           @NotBlank @Size(max = 40) String terminalId) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    record UnlockRequest(@NotBlank String refreshToken,
                         @NotNull UnlockMethod method,
                         @NotBlank @Size(max = 200) String secret) {
    }

    @ExceptionHandler(SessionClosedException.class)
    ProblemDetail sessionClosed(SessionClosedException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setType(URI.create("https://pharmacore.io/problems/" + SessionClosedException.CODE));
        problem.setTitle("Session fermée");
        problem.setProperty("code", SessionClosedException.CODE);
        problem.setProperty("reason", e.reason());
        return problem;
    }

    record Profile(UUID userId, UUID tenantId, String displayName, Set<String> roles, Set<String> permissions,
                   BigDecimal maxDiscountPercent) {
    }

    /** @param sessionLocked the session is locked on the server: the terminal shows its lock screen */
    record TokenResponse(String accessToken, Instant accessExpiresAt, String refreshToken, String terminalToken,
                         boolean mustChangePassword, Profile profile, IdlePolicy sessionPolicy,
                         boolean sessionLocked) {

        static TokenResponse of(Tokens tokens) {
            var user = tokens.user();
            return new TokenResponse(tokens.accessToken(), tokens.accessExpiresAt(), tokens.refreshToken(),
                    tokens.terminalToken(), tokens.mustChangePassword(),
                    new Profile(user.userId(), user.tenant().value(), user.displayName(), user.roles(),
                            user.permissions(), user.maxDiscountPercent()),
                    tokens.idlePolicy(), tokens.sessionLocked());
        }
    }
}
