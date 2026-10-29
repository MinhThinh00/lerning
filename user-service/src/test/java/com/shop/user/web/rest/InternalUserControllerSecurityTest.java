package com.shop.user.web.rest;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shop.common.autoconfigure.ShopCommonAutoConfiguration;
import com.shop.user.application.dto.UserDtos.UserContact;
import com.shop.user.application.service.UserContactService;
import com.shop.user.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** docs/10 T8 bước 5: token user → 403 (kể cả ADMIN), token service → 200. */
@WebMvcTest(InternalUserController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ShopCommonAutoConfiguration.class)
class InternalUserControllerSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserContactService contacts;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/internal/users/u1/contact")).andExpect(status().isUnauthorized());
    }

    @Test
    void userTokenIs403_evenAdmin() throws Exception {
        mvc.perform(get("/api/internal/users/u1/contact").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/internal/users/u1/contact").with(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("user:read"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void serviceTokenGetsContact() throws Exception {
        given(contacts.contact("u1")).willReturn(new UserContact("u1", "user1@shop.local", "User", "vi"));

        mvc.perform(get("/api/internal/users/u1/contact").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("user1@shop.local"))
            .andExpect(jsonPath("$.languageKey").value("vi"));
    }
}
