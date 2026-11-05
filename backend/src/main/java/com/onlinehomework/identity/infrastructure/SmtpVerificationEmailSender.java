package com.onlinehomework.identity.infrastructure;

import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.identity.application.VerificationEmailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpVerificationEmailSender implements VerificationEmailSender {
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String from;
    private final String publicBaseUrl;

    public SmtpVerificationEmailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                                       @Value("${app.email.from}") String from,
                                       @Value("${app.public-base-url}") String publicBaseUrl) {
        this.mailSenderProvider = mailSenderProvider;
        this.from = from;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void send(String recipient, String token) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new DomainException("EMAIL_VERIFICATION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "Email verification is temporarily unavailable.");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject("Verify your Online Homework account");
        message.setText("Use this one-time verification token in the app: " + token + "\n\n"
                + "This token expires soon. If you did not create this account, ignore this message.");
        try {
            mailSender.send(message);
        } catch (RuntimeException exception) {
            throw new DomainException("EMAIL_VERIFICATION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "Email verification could not be delivered.");
        }
    }
}
