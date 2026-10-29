package com.shop.common.messaging.it;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * "Service" tối thiểu để test shop-common như một service thật dùng nó: chỉ auto-configuration,
 * không component scan (listener của từng test được @Import riêng).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class MessagingTestApplication {
}
