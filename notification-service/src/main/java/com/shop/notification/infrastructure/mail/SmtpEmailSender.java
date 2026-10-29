package com.shop.notification.infrastructure.mail;

import com.shop.notification.application.port.EmailSender;
import com.shop.notification.config.NotificationProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Gửi qua SMTP (spring.mail.*). Dev: Mailpit (deploy/infra/mail), xem mail ở http://localhost:8025. */
@Component
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    SmtpEmailSender(JavaMailSender mail, NotificationProperties props) {
        this.mail = mail;
        this.from = props.mailFrom();
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        mail.send(msg);
    }
}
