package com.devopsaaas.identity.api;

import com.devopsaaas.identity.LoginService;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.shared.security.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuthController {

    private final LoginService loginService;

    AuthController(LoginService loginService) {
        this.loginService = loginService;
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 256) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=<redacted>]";
        }
    }

    record LoginResponse(String accessToken, String tokenType, long expiresIn) {

        @Override
        public String toString() {
            return "LoginResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
        }
    }

    record MeResponse(UUID userId, UUID organizationId, String email, Set<Permission> permissions) {
    }

    @PostMapping("/auth/login")
    @PublicEndpoint
    LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        LoginService.LoginResult result = loginService.login(request.email(), request.password(), http.getRemoteAddr());
        return new LoginResponse(result.accessToken(), "Bearer", result.expiresInSeconds());
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    MeResponse me(@AuthenticationPrincipal CurrentUser user) {
        return new MeResponse(user.userId(), user.organizationId(), user.email(), user.permissions());
    }
}
