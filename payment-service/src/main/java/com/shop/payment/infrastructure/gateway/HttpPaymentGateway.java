package com.shop.payment.infrastructure.gateway;

import com.shop.payment.application.port.PaymentGateway;
import com.shop.payment.config.PaymentProperties;
import java.net.http.HttpClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter HTTP tới cổng thanh toán. Cùng 1 code cho cổng giả (FakeGatewayController) và cổng thật có API
 * tương tự: chỉ đổi {@code payment.gateway.base-url}.
 */
@Component
class HttpPaymentGateway implements PaymentGateway {

    record CreateCharge(String merchantRef, String orderId, String amount, String currency, String callbackUrl) {
    }

    record ChargeStatus(String status, String providerTxnId, String reason) {
    }

    private final RestClient http;
    private final String callbackUrl;

    HttpPaymentGateway(PaymentProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(props.gateway().timeout()).build());
        factory.setReadTimeout(props.gateway().timeout());
        this.http = RestClient.builder().baseUrl(props.gateway().baseUrl()).requestFactory(factory).build();
        this.callbackUrl = props.gateway().callbackUrl();
    }

    @Override
    public void submit(ChargeRequest req) {
        try {
            http.post().uri("/charges")
                .body(new CreateCharge(req.paymentId(), req.orderId(), req.amount().toPlainString(), req.currency(), callbackUrl))
                .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new GatewayUnavailableException("submit " + req.paymentId() + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public GatewayStatus status(String paymentId) {
        try {
            ChargeStatus st = http.get().uri("/charges/{ref}", paymentId).retrieve().body(ChargeStatus.class);
            return new GatewayStatus(GatewayStatus.State.valueOf(st.status()), st.providerTxnId(), st.reason());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return new GatewayStatus(GatewayStatus.State.NOT_FOUND, null, null);
            }
            throw new GatewayUnavailableException("status " + paymentId + " failed: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new GatewayUnavailableException("status " + paymentId + " failed: " + e.getMessage(), e);
        }
    }
}
