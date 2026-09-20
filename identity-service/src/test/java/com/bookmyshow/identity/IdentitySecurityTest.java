package com.bookmyshow.identity;

import com.bookmyshow.identity.auth.*;
import com.bookmyshow.identity.auth.dto.*;
import com.bookmyshow.identity.security.SecurityConfiguration;
import com.bookmyshow.identity.user.*;
import com.bookmyshow.identity.user.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AuthController.class, UserController.class})
@Import(SecurityConfiguration.class)
class IdentitySecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean UserService users;

    @Test void registerAndLoginArePublic() throws Exception {
        when(auth.login(any())).thenReturn(new TokenResponse("token", "Bearer", 900));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"password\":\"Password@123\",\"firstName\":\"A\",\"lastName\":\"B\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"password\":\"Password@123\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void invalidRegistrationReturns400() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.email").exists());
        verifyNoInteractions(auth);
    }
    @Test void missingOrInvalidTokenReturns401Problem() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }
    @Test void expiredTokenIsNotAuthenticated() throws Exception {
        var expired = TestTokens.token("1", "USER", "bookmyshow-identity", List.of("bookmyshow-api"), Instant.now().minusSeconds(5));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
    }
    @Test void validJwtPopulatesPrincipalAndReturnsSafeUserDto() throws Exception {
        when(users.currentUser("1")).thenReturn(new UserResponse(1L, "a@b.com", "A", "B", Role.USER, true, null, null));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + TestTokens.token("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("a@b.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(header().doesNotExist("Set-Cookie"));
        verify(users).currentUser("1");
    }
    @Test void userCannotAccessAdminButAdminCan() throws Exception {
        mvc.perform(get("/api/v1/users/admin/status").header("Authorization", "Bearer " + TestTokens.token("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users/admin/status").header("Authorization", "Bearer " + TestTokens.token("ADMIN")))
                .andExpect(status().isOk());
    }
    @Test void spoofedIdentityHeadersDoNotAuthenticate() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("X-User-Id", "1").header("X-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test void malformedSignedRoleReturns401InsteadOfATypeError() throws Exception {
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer("bookmyshow-identity").audience(List.of("bookmyshow-api")).subject("1")
                .expiresAt(Instant.now().plusSeconds(30)).claim("role", List.of("ADMIN")).build();
        var token = TestTokens.encoder().encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }
}
