package com.onlinehomework.identity.application;

import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.identity.api.AuthDtos.LoginRequest;
import com.onlinehomework.identity.api.AuthDtos.LoginResponse;
import com.onlinehomework.identity.api.AuthDtos.MeResponse;
import com.onlinehomework.identity.api.AuthDtos.RegisterRequest;
import com.onlinehomework.identity.api.AuthDtos.RegisterResponse;
import com.onlinehomework.identity.domain.EmailVerificationToken;
import com.onlinehomework.identity.domain.UserAccount;
import com.onlinehomework.identity.domain.UserRole;
import com.onlinehomework.identity.persistence.EmailVerificationTokenRepository;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository users;
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordEncoder passwordEncoder;
    private final VerificationEmailSender emailSender;
    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final String jwtIssuer;
    private final Duration accessTokenTtl;
    private final Duration verificationTtl;

    public AuthService(UserAccountRepository users,
                       EmailVerificationTokenRepository verificationTokens,
                       PasswordEncoder passwordEncoder,
                       VerificationEmailSender emailSender,
                       JwtEncoder jwtEncoder,
                       Clock clock,
                       @Value("${app.security.jwt-issuer}") String jwtIssuer,
                       @Value("${app.security.access-token-ttl}") Duration accessTokenTtl,
                       @Value("${app.email.verification-ttl}") Duration verificationTtl) {
        this.users = users;
        this.verificationTokens = verificationTokens;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.jwtIssuer = jwtIssuer;
        this.accessTokenTtl = accessTokenTtl;
        this.verificationTtl = verificationTtl;
    }

    @Transactional
    public RegisterResponse registerStudent(RegisterRequest request) {
        String email = request.email().trim();
        String normalizedEmail = UserAccount.normalizeEmail(email);
        if (users.existsByNormalizedEmail(normalizedEmail)) {
            throw DomainException.conflict("EMAIL_ALREADY_REGISTERED", "This email is already registered.");
        }
        Instant now = clock.instant();
        UserAccount user = users.save(new UserAccount(UUID.randomUUID(), email,
                passwordEncoder.encode(request.password()), UserRole.STUDENT, false, true,
                request.displayName().trim(), now));
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        verificationTokens.deleteAllByUserIdAndConsumedAtIsNull(user.getId());
        verificationTokens.save(new EmailVerificationToken(UUID.randomUUID(), user.getId(), hash(rawToken),
                now.plus(verificationTtl)));
        emailSender.send(user.getEmail(), rawToken);
        return new RegisterResponse(user.getId(), user.getEmail(), user.getRole().name(),
                user.isEmailVerified(), true);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        String tokenHash = hash(rawToken);
        EmailVerificationToken token = verificationTokens.findByTokenHashAndConsumedAtIsNull(tokenHash)
                .orElseThrow(() -> DomainException.invalid("VERIFICATION_TOKEN_INVALID", "Verification token is invalid."));
        Instant now = clock.instant();
        if (now.isAfter(token.getExpiresAt())) {
            throw DomainException.invalid("VERIFICATION_TOKEN_EXPIRED", "Verification token has expired.");
        }
        UserAccount user = users.findById(token.getUserId())
                .orElseThrow(() -> DomainException.notFound("ACCOUNT_NOT_FOUND", "Account was not found."));
        user.verifyEmail();
        token.consume(now);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = UserAccount.normalizeEmail(request.email());
        UserAccount user = users.findByNormalizedEmail(normalizedEmail)
                .orElseThrow(() -> new DomainException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED,
                        "Email or password is incorrect."));
        if (!user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new DomainException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED,
                    "Email or password is incorrect.");
        }
        if (!user.isEmailVerified()) {
            throw new DomainException("EMAIL_NOT_VERIFIED", HttpStatus.UNAUTHORIZED,
                    "Verify the account email before signing in.");
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtIssuer)
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("email", user.getEmail())
                .claim("display_name", user.getDisplayName())
                .claim("role", user.getRole().name())
                .claim("email_verified", user.isEmailVerified())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, expiresAt,
                new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole().name(),
                        user.isEmailVerified()));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
