package com.shop.product.web.rest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.mysql.MySQLContainer;

/**
 * DoD Phase 4 + data scoping (docs/10 T9), chạy end-to-end: HTTP → security → service → JPA → MySQL thật
 * (Liquibase tạo schema + seed context dev). jwt() giả lập token Keycloak đã verify, authorities = những gì
 * ShopJwtAuthoritiesConverter map ra từ token thật (docs/03-SECURITY.md ma trận RBAC).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductApiIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36").withDatabaseName("db_product");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;   // không gọi Keycloak lúc test

    // ── DoD: GET không cần token ──

    @Test
    void anonymousCanReadSeededProducts() throws Exception {
        mvc.perform(get("/api/products/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sku").value("PHONE-001"))
            .andExpect(jsonPath("$.price").value(22990000.00))
            .andExpect(jsonPath("$.sellerId").doesNotExist());   // view public không lộ seller

        // Laptop (2): không test nào tạo thêm vào danh mục này → đúng 3 sản phẩm seed
        mvc.perform(get("/api/products").param("categoryId", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content[*].sku", Matchers.contains("LAPTOP-001", "LAPTOP-002", "LAPTOP-003")));

        mvc.perform(get("/api/categories")).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name", Matchers.hasItems("Điện thoại", "Laptop", "Phụ kiện")));
    }

    /** API cho order-service: id không tồn tại thì bị bỏ qua, không lỗi. */
    @Test
    void getByIdsSkipsUnknownIds() throws Exception {
        mvc.perform(get("/api/products").param("ids", "1", "2", "999999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[*].id", Matchers.containsInAnyOrder(1, 2)));
    }

    @Test
    void unknownProductIs404() throws Exception {
        mvc.perform(get("/api/products/999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── DoD: seller1 tạo được; user1 nhận 403 ──

    @Test
    void createWithoutTokenIs401() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(newProductJson(sku())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotCreateProduct() throws Exception {
        mvc.perform(post("/api/products").with(customer()).contentType(MediaType.APPLICATION_JSON).content(newProductJson(sku())))
            .andExpect(status().isForbidden());
    }

    @Test
    void sellerCreatesProductOwnedByThemAndItIsPubliclyVisible() throws Exception {
        String sku = sku();
        long id = create(seller("seller-1"), sku);

        mvc.perform(get("/api/seller/products/" + id).with(seller("seller-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sellerId").value("seller-1"))
            .andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/products/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.sku").value(sku));
    }

    @Test
    void duplicateSkuIs409() throws Exception {
        String sku = sku();
        create(seller("seller-1"), sku);
        mvc.perform(post("/api/products").with(seller("seller-1")).contentType(MediaType.APPLICATION_JSON).content(newProductJson(sku)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SKU_TAKEN"));
    }

    @Test
    void invalidPriceIs400() throws Exception {
        mvc.perform(post("/api/products").with(seller("seller-1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"%s\",\"name\":\"X\",\"price\":0}".formatted(sku())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCategoryIs400() throws Exception {
        mvc.perform(post("/api/products").with(seller("seller-1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"%s\",\"name\":\"X\",\"price\":1000,\"categoryId\":999}".formatted(sku())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CATEGORY"));
    }

    // ── T9: data scoping, không IDOR ──

    /** seller2 đổi id trên URL để sửa sản phẩm của seller1 → 404 (không phải 403, để không lộ id có tồn tại). */
    @Test
    void sellerCannotTouchAnotherSellersProduct() throws Exception {
        long id = create(seller("seller-1"), sku());

        mvc.perform(put("/api/products/" + id).with(seller("seller-2")).contentType(MediaType.APPLICATION_JSON)
                .content(updateJson(1, null, true)))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/seller/products/" + id).with(seller("seller-2")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/seller/products").param("size", "100").with(seller("seller-2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].id", Matchers.not(Matchers.hasItem((int) id))));

        // Giá không đổi
        mvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.price").value(1500000.00));
    }

    @Test
    void sellerSeesOnlyOwnProductsAdminSeesAll() throws Exception {
        String owner = UUID.randomUUID().toString();   // giống sub thật của Keycloak (36 ký tự)
        create(seller(owner), sku());
        create(seller(owner), sku());

        mvc.perform(get("/api/seller/products").with(seller(owner)))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[*].sellerId", Matchers.everyItem(Matchers.is(owner))));
        mvc.perform(get("/api/seller/products").with(admin()))
            .andExpect(jsonPath("$.totalElements", Matchers.greaterThanOrEqualTo(12)));   // 10 seed + 2
    }

    @Test
    void adminCanEditAnyProduct() throws Exception {
        long id = create(seller("seller-1"), sku());
        mvc.perform(put("/api/products/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(updateJson(1990000, 0L, true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.price").value(1990000))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.sellerId").value("seller-1"));   // owner không đổi
    }

    /** Client sửa dựa trên bản đã cũ (version 0 trong khi DB đã là 1) → 409, không ghi đè. */
    @Test
    void staleVersionIs409() throws Exception {
        long id = create(seller("seller-1"), sku());
        mvc.perform(put("/api/products/" + id).with(seller("seller-1")).contentType(MediaType.APPLICATION_JSON)
                .content(updateJson(2000000, 0L, true)))
            .andExpect(status().isOk());
        mvc.perform(put("/api/products/" + id).with(seller("seller-1")).contentType(MediaType.APPLICATION_JSON)
                .content(updateJson(3000000, 0L, true)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void deactivatedProductDisappearsFromPublicApi() throws Exception {
        long id = create(seller("seller-1"), sku());
        mvc.perform(put("/api/products/" + id).with(seller("seller-1")).contentType(MediaType.APPLICATION_JSON)
                .content(updateJson(1500000, null, false)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/products").param("ids", String.valueOf(id))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/seller/products/" + id).with(seller("seller-1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void onlyAdminCreatesCategories() throws Exception {
        String body = "{\"name\":\"Cat %s\"}".formatted(UUID.randomUUID());
        mvc.perform(post("/api/categories").with(seller("seller-1")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/categories").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/categories").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());
    }

    // ── helpers ──

    private long create(RequestPostProcessor who, String sku) throws Exception {
        String body = mvc.perform(post("/api/products").with(who).contentType(MediaType.APPLICATION_JSON).content(newProductJson(sku)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static String sku() {
        return "IT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String newProductJson(String sku) {
        return "{\"sku\":\"%s\",\"name\":\"Ốp lưng\",\"price\":1500000,\"categoryId\":3}".formatted(sku);
    }

    private static String updateJson(long price, Long version, boolean active) {
        return "{\"name\":\"Ốp lưng v2\",\"price\":%d,\"categoryId\":3,\"active\":%s,\"version\":%s}"
            .formatted(price, active, version);
    }

    /** ROLE_USER → product:read (docs/03 ma trận RBAC) */
    private static RequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("user-1"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("product:read"));
    }

    private static RequestPostProcessor seller(String sub) {
        return jwt().jwt(j -> j.subject(sub))
            .authorities(new SimpleGrantedAuthority("ROLE_SELLER"), new SimpleGrantedAuthority("product:read"),
                new SimpleGrantedAuthority("product:write"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("admin-1"))
            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("product:read"),
                new SimpleGrantedAuthority("product:write"));
    }
}
