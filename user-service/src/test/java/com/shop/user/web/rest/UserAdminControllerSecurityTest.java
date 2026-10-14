package com.shop.user.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shop.common.autoconfigure.ShopCommonAutoConfiguration;
import com.shop.common.web.PageResponse;
import com.shop.user.application.dto.UserDtos.UserDetail;
import com.shop.user.application.service.UserAdminService;
import com.shop.user.config.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Kiểm tra lớp phân quyền thứ nhất (@PreAuthorize) theo đúng ma trận RBAC.
 * jwt() giả lập token đã verify sẵn, authorities = những gì converter sẽ map ra từ token thật.
 */
@WebMvcTest(UserAdminController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ShopCommonAutoConfiguration.class)
class UserAdminControllerSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserAdminService users;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotSearchUsers() throws Exception {
        mvc.perform(get("/api/admin/users").with(customer())).andExpect(status().isForbidden());
    }

    @Test
    void supportCanSearchUsers() throws Exception {
        given(users.search(any(), anyInt(), anyInt())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/admin/users").param("keyword", "john").with(support()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void supportCanResetPassword() throws Exception {
        mvc.perform(post("/api/admin/users/u1/reset-password").with(support())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"NewPass@123\"}"))
            .andExpect(status().isNoContent());
    }

    @Test
    void supportCannotDelete() throws Exception {
        mvc.perform(delete("/api/admin/users/u1").with(support())).andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreate() throws Exception {
        given(users.create(any(), any())).willReturn(new UserDetail("new-id", "jane", "jane@shop.local", "Jane", "Doe",
            true, false, Instant.now(), Set.of("ROLE_USER"), null, null, "vi"));
        mvc.perform(post("/api/admin/users").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"jane","email":"jane@shop.local","password":"Secret@123"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("new-id"));
    }

    @Test
    void invalidBodyIs400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/admin/users").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"jane","email":"not-an-email","password":"short"}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        mvc.perform(get("/api/admin/users").param("size", "500").with(admin()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanDelete() throws Exception {
        mvc.perform(delete("/api/admin/users/u1").with(admin())).andExpect(status().isNoContent());
        org.mockito.Mockito.verify(users).delete(any(), eq("u1"));
    }

    private static RequestPostProcessor customer() {
        return token("customer-id", "ROLE_USER", "product:read", "order:read", "order:write");
    }

    private static RequestPostProcessor support() {
        return token("support-id", "ROLE_SUPPORT", "user:read", "user:reset-password");
    }

    private static RequestPostProcessor admin() {
        return token("admin-id", "ROLE_ADMIN", "user:read", "user:create", "user:update", "user:delete",
            "user:reset-password", "user:assign-role");
    }

    private static RequestPostProcessor token(String sub, String... authorities) {
        return jwt().jwt(j -> j.subject(sub))
            .authorities(java.util.Arrays.stream(authorities).<GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
    }
}
