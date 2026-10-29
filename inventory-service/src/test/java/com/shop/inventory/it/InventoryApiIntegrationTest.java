package com.shop.inventory.it;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** REST tồn kho + ma trận quyền (docs/03 §5, docs/04 §7). */
class InventoryApiIntegrationTest extends InventoryIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void seededStockIsReadableWithInventoryRead() throws Exception {
        mvc.perform(get("/api/inventory/10").with(manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.productId").value(10))
            .andExpect(jsonPath("$.available").isNumber());
    }

    @Test
    void readingStockRequiresPermission() throws Exception {
        mvc.perform(get("/api/inventory/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/inventory/1").with(customer())).andExpect(status().isForbidden());
    }

    @Test
    void firstAdjustmentOpensInventoryForNewProduct() throws Exception {
        long productId = 900_000_000L + (System.nanoTime() % 1_000_000);
        mvc.perform(get("/api/inventory/" + productId).with(manager())).andExpect(status().isNotFound());

        mvc.perform(adjust(productId, 50).with(manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(50));
        mvc.perform(adjust(productId, -20).with(manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(30));
        mvc.perform(adjust(productId, -31).with(manager()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void adjustmentRequiresInventoryWrite() throws Exception {
        long productId = newProduct(5);
        mvc.perform(adjust(productId, 5).with(customer())).andExpect(status().isForbidden());
    }

    @Test
    void reserveEndpointForBenchmarkUsesStrategyParam() throws Exception {
        long productId = newProduct(1);
        String body = "{\"orderId\":\"%s\",\"items\":[{\"productId\":%d,\"quantity\":1}]}";

        mvc.perform(post("/api/inventory/reserve").param("strategy", "L1").with(service())
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(UUID.randomUUID(), productId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("RESERVED"));
        mvc.perform(post("/api/inventory/reserve").param("strategy", "L1").with(service())
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(UUID.randomUUID(), productId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.reason").value("OUT_OF_STOCK"));
    }

    @Test
    void unknownStrategyIs400() throws Exception {
        mvc.perform(post("/api/inventory/reserve").param("strategy", "L9").with(service())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"%s\",\"items\":[{\"productId\":1,\"quantity\":1}]}".formatted(UUID.randomUUID())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reserveEndpointIsNotForSellers() throws Exception {
        mvc.perform(post("/api/inventory/reserve").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"x\",\"items\":[{\"productId\":1,\"quantity\":1}]}"))
            .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adjust(long productId, int delta) {
        return post("/api/inventory/" + productId + "/adjustments").contentType(MediaType.APPLICATION_JSON)
            .content("{\"delta\":%d,\"reason\":\"test\"}".formatted(delta));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager-1")).authorities(new SimpleGrantedAuthority("ROLE_INVENTORY_MANAGER"),
            new SimpleGrantedAuthority("inventory:read"), new SimpleGrantedAuthority("inventory:write"));
    }

    private static RequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("user-1")).authorities(new SimpleGrantedAuthority("ROLE_USER"),
            new SimpleGrantedAuthority("product:read"));
    }

    private static RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("svc")).authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }
}
