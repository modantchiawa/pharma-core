package com.pharmacore.identity.web;

import com.pharmacore.identity.application.LocalAuthService;
import com.pharmacore.identity.application.LocalAuthService.Tokens;
import com.pharmacore.identity.domain.IdlePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

    record Profile(UUID userId, UUID tenantId, String displayName, Set<String> roles, Set<String> permissions,
                   BigDecimal maxDiscountPercent) {
    }

    record TokenResponse(String accessToken, Instant accessExpiresAt, String refreshToken, String terminalToken,
                         boolean mustChangePassword, Profile profile, IdlePolicy sessionPolicy) {

        static TokenResponse of(Tokens tokens) {
            var user = tokens.user();
            return new TokenResponse(tokens.accessToken(), tokens.accessExpiresAt(), tokens.refreshToken(),
                    tokens.terminalToken(), tokens.mustChangePassword(),
                    new Profile(user.userId(), user.tenant().value(), user.displayName(), user.roles(),
                            user.permissions(), user.maxDiscountPercent()),
                    tokens.idlePolicy());
        }
    }
}
