package com.shop.notification.application.port;

/** Port gửi email. Ném exception = chưa gửi được (dispatcher sẽ thử lại). */
public interface EmailSender {

    void send(String to, String subject, String body);
}
