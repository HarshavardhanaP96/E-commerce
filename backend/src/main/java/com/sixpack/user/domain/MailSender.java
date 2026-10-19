package com.sixpack.user.domain;

/** Outbound mail port. Swap the logging adapter for SMTP/SES/etc. in production. */
public interface MailSender {

    void sendPasswordReset(String email, String name, String resetLink);

    void sendEmailVerification(String email, String name, String verifyLink);
}
