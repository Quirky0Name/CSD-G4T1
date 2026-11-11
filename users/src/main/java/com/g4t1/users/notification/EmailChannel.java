package com.g4t1.users.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Email through the SMTP server in MAIL_HOST; with none set there's nothing to send with
@Component
public class EmailChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;

    public EmailChannel(ObjectProvider<JavaMailSender> sender, @Value("${spring.mail.host:}") String host,
                        @Value("${users.mail-from:}") String from) {
        this.sender = sender;
        this.host = host;
        this.from = from;
    }

    // a failure logs the user and the exception's class only: its message can hold the address
    public Outcome send(UUID userId, String address, Message message) {
        if (host.isBlank()) {
            return Outcome.NOT_CONFIGURED;
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        if (!from.isBlank()) {
            mail.setFrom(from);
        }
        mail.setTo(address);
        mail.setSubject(message.subject());
        mail.setText(message.text());
        try {
            sender.getObject().send(mail);
            return Outcome.SENT;
        } catch (MailException e) {
            log.warn("emailing user {} failed: {}", userId, e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }
}
