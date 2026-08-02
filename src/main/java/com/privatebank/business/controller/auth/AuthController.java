package com.privatebank.business.controller.auth;

import com.privatebank.business.dto.auth.LoginRequest;
import com.privatebank.business.dto.auth.LoginResponse;
import com.privatebank.business.dto.auth.UserProfileResponse;
import com.privatebank.business.service.auth.AuthService;
import com.privatebank.business.security.CurrentUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserProfileResponse me(@AuthenticationPrincipal CurrentUserPrincipal principal) {
        return authService.profile(principal);
    }
}
