package com.onlinehomework.identity.persistence;

import com.onlinehomework.identity.domain.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findByTokenHashAndConsumedAtIsNull(String tokenHash);

    void deleteAllByUserIdAndConsumedAtIsNull(UUID userId);
}
