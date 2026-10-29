package com.shop.order.application.port;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * Port: tra giá ở product-service (nguồn sự thật về giá). Giao tiếp đồng bộ CHỈ để đọc (docs/00 §4);
 * mọi thay đổi trạng thái liên service đi qua Kafka.
 */
public interface ProductCatalog {

    /** Sản phẩm không tồn tại / ngừng bán thì không có trong kết quả. */
    List<CatalogProduct> findByIds(Collection<Long> ids);

    record CatalogProduct(long id, String sku, String name, BigDecimal price, String currency) {
    }

    /** product-service không trả lời được (chết, timeout, 5xx). */
    class CatalogUnavailableException extends RuntimeException {

        public CatalogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
