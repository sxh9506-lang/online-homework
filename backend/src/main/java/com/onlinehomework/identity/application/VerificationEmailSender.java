package com.onlinehomework.identity.application;

public interface VerificationEmailSender {
    void send(String recipient, String token);
}
