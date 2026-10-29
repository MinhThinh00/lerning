package com.shop.inventory.application.stock;

import com.shop.common.web.error.BusinessException;
import com.shop.common.web.error.NotFoundException;
import com.shop.inventory.application.reservation.StockCounterSync;
import com.shop.inventory.domain.model.Inventory;
import com.shop.inventory.domain.repository.InventoryRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xem và điều chỉnh tồn kho. Đọc thẳng DB, không cache (docs/04 §7: tồn kho đổi liên tục, cache không bao giờ hit
 * với sản phẩm hot, còn cache local thì lệch giữa các replica).
 *
 * Tạo sản phẩm ở product-service KHÔNG tạo tồn kho: lần nhập kho đầu tiên ở đây mở dòng inventory.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final InventoryRepository inventory;
    private final StockCounterSync counterSync;

    public StockService(InventoryRepository inventory, StockCounterSync counterSync) {
        this.inventory = inventory;
        this.counterSync = counterSync;
    }

    @Transactional(readOnly = true)
    public StockView get(long productId) {
        return inventory.findByProductId(productId).map(StockView::from)
            .orElseThrow(() -> new NotFoundException("Inventory of product", productId));
    }

    /**
     * delta > 0 nhập kho, delta < 0 xuất kho (hỏng, thất lạc...). UPDATE nguyên tử nên chạy song song với
     * reserve không làm mất cập nhật của nhau.
     */
    @Transactional
    public StockView adjust(String actor, long productId, int delta, String reason) {
        if (delta == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DELTA", "delta must not be 0");
        }
        if (inventory.adjustAvailable(productId, delta) == 0) {
            if (inventory.findByProductId(productId).isPresent()) {
                throw new BusinessException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                    "Cannot remove " + (-delta) + " units: not enough available stock");
            }
            if (delta < 0) {
                throw new NotFoundException("Inventory of product", productId);
            }
            open(productId, delta);
            counterSync.loadAfterCommit(productId, delta);
        } else {
            counterSync.adjustAfterCommit(productId, delta);
        }
        log.info("Stock adjusted by {}: product={} delta={} reason={}", actor, productId, delta, reason);
        return get(productId);
    }

    private void open(long productId, int initial) {
        try {
            inventory.saveAndFlush(Inventory.open(productId, initial));
        } catch (DataIntegrityViolationException race) {
            // Lần nhập kho đầu tiên song song từ 2 request: bên thua trả 409, client gửi lại sẽ đi nhánh UPDATE
            throw new BusinessException(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Inventory was created concurrently, retry");
        }
    }

    public record StockView(long productId, int available, int reserved, int sold, boolean active, Instant updatedAt) {

        static StockView from(Inventory inv) {
            return new StockView(inv.getProductId(), inv.getAvailable(), inv.getReserved(), inv.getSold(),
                inv.isActive(), inv.getUpdatedAt());
        }
    }
}
