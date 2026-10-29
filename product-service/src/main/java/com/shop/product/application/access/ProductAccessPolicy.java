package com.shop.product.application.access;

import com.shop.common.web.error.NotFoundException;
import com.shop.product.domain.model.Product;
import java.util.Optional;

/**
 * Lớp phân quyền thứ hai: theo ĐỐI TƯỢNG (docs/10 T9).
 *
 * @PreAuthorize("hasAuthority('product:write')") chỉ trả lời "có được sửa sản phẩm không".
 * Policy này trả lời "có được sửa SẢN PHẨM NÀY không": seller chỉ đụng được sản phẩm của mình, admin đụng mọi thứ.
 * Thiếu lớp này → seller1 sửa giá sản phẩm của seller2 chỉ bằng cách đổi id trên URL (IDOR, OWASP API1).
 */
public final class ProductAccessPolicy {

    private ProductAccessPolicy() {
    }

    /** Phạm vi danh sách: empty = thấy hết (admin), có giá trị = chỉ sản phẩm của seller này. */
    public static Optional<String> sellerScope(Caller caller) {
        return caller.isAdmin() ? Optional.empty() : Optional.of(caller.id());
    }

    /**
     * Không có quyền thì trả 404 giống hệt "không tồn tại", KHÔNG phải 403:
     * 403 cho kẻ dò biết id đó có thật (và thuộc người khác).
     */
    public static void checkCanModify(Caller caller, Product product) {
        if (!caller.isAdmin() && !product.isOwnedBy(caller.id())) {
            throw new NotFoundException("Product", product.getId());
        }
    }
}
