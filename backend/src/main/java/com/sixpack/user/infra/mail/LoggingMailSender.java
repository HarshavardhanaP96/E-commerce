package com.sixpack.user.infra.mail;

import com.sixpack.user.application.UserProperties;
import com.sixpack.user.domain.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder adapter until a mail provider is chosen. Logs the link only when
 * {@code app.user.mail-log-links=true} (local/test); in prod it never logs secrets.
 */
@Component
class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    private final UserProperties props;

    LoggingMailSender(UserProperties props) {
        this.props = props;
    }

    @Override
    public void sendPasswordReset(String email, String name, String resetLink) {
        if (props.mailLogLinks()) {
            log.info("[dev-mail] password reset for {}: {}", email, resetLink);
        } else {
            log.warn("No mail provider configured; password reset email was not sent");
        }
    }
}
