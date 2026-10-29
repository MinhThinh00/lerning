package com.shop.inventory.application.reservation;

import java.util.List;
import java.util.OptionalLong;

/**
 * Bộ đếm tồn kho ở Redis ({@code inv:stock:<productId>} ≈ available của DB), dùng làm CỔNG cho L5: hết hàng thì
 * từ chối ngay, không chạm DB. DB vẫn là nguồn sự thật (docs/04 §5): counter lệch chỉ ảnh hưởng hiệu năng,
 * không gây oversell, vì sau cổng vẫn là UPDATE có điều kiện của L1.
 *
 * Mọi method trừ {@link #tryDeduct} là best-effort: Redis lỗi thì log và bỏ qua, reconcile job kéo về khớp DB.
 */
public interface StockCounter {

    enum DeductResult { OK, INSUFFICIENT, NOT_LOADED }

    /**
     * All-or-nothing, nguyên tử (Lua): đủ hàng cho MỌI item thì trừ hết, thiếu 1 item thì không trừ gì.
     * @throws RuntimeException Redis không trả lời được (L5 rơi về đi thẳng DB)
     */
    Deduction tryDeduct(List<Item> items);

    /** Trả lại phần đã trừ khi bước DB thất bại. */
    void restore(List<Item> items);

    /** available đổi delta (nhả hàng, nhập kho, giữ hàng bằng strategy không qua Redis). Chỉ khi key đã có. */
    void adjust(long productId, int delta);

    /** Nạp counter nếu chưa có (SET NX: không đè counter replica khác đang dùng). */
    void loadIfAbsent(long productId, int available);

    OptionalLong current(long productId);

    /** Ghi đè theo DB (reconcile). */
    void overwrite(long productId, int available);

    /**
     * Đánh dấu "nghi lệch" (có hạn). @return true nếu lần này mới đánh dấu, false nếu đã bị đánh dấu từ lần
     * kiểm tra trước (→ lệch 2 lần liên tiếp, nên sửa).
     */
    boolean markSuspect(long productId);

    void clearSuspect(long productId);

    /** @param shortProductId sản phẩm thiếu hàng / chưa nạp (khi result != OK) */
    record Deduction(DeductResult result, long shortProductId) {
    }
}
