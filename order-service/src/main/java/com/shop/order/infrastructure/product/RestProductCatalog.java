package com.shop.order.infrastructure.product;

import com.shop.order.application.port.ProductCatalog;
import com.shop.order.config.OrderProperties;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gọi GET /api/products?ids=... (public, không cần token: docs/03 §6) bằng RestClient của Spring 7.
 * Thay cho Feign + Consul trong docs (stack JHipster): stack viết tay không có Spring Cloud. Trong Docker,
 * "product-service" là tên DNS của compose, tự chia đều cho các replica.
 *
 * Timeout bắt buộc: không có nó, product-service treo → thread request đặt hàng treo theo → cạn thread pool.
 */
@Component
class RestProductCatalog implements ProductCatalog {

    private final RestClient http;

    RestProductCatalog(OrderProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(props.productTimeout()).build());
        factory.setReadTimeout(props.productTimeout());
        this.http = RestClient.builder().baseUrl(props.productServiceUrl()).requestFactory(factory).build();
    }

    @Override
    public List<CatalogProduct> findByIds(Collection<Long> ids) {
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            CatalogProduct[] body = http.get().uri("/api/products?ids={ids}", csv).retrieve().body(CatalogProduct[].class);
            return body == null ? List.of() : Arrays.asList(body);
        } catch (RestClientException ex) {
            throw new CatalogUnavailableException("product-service call failed: " + ex.getMessage(), ex);
        }
    }
}
