package com.onlinehomework.identity.api;

import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.identity.api.AuthDtos.LoginRequest;
import com.onlinehomework.identity.api.AuthDtos.LoginResponse;
import com.onlinehomework.identity.api.AuthDtos.MeResponse;
import com.onlinehomework.identity.api.AuthDtos.RegisterRequest;
import com.onlinehomework.identity.api.AuthDtos.RegisterResponse;
import com.onlinehomework.identity.api.AuthDtos.VerifyEmailRequest;
import com.onlinehomework.identity.application.AuthService;
import com.onlinehomework.identity.domain.UserAccount;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;
    private final UserAccountRepository users;

    public AuthController(AuthService authService, UserAccountRepository users) {
        this.authService = authService;
        this.users = users;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.registerStudent(request);
    }

    @PostMapping("/auth/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UserAccount user = users.findById(userId).orElseThrow(() ->
                DomainException.notFound("ACCOUNT_NOT_FOUND", "Account was not found."));
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole().name(),
                user.isEmailVerified());
    }
}
