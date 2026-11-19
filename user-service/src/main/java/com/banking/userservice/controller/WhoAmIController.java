package com.banking.userservice.controller;

import com.banking.userservice.dto.WhoAmIResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shows the result of token validation: who the caller is and which authorities Spring gave them.
 *
 * <p>{@code @AuthenticationPrincipal Jwt} injects the already-verified token. Controllers never
 * parse the Authorization header themselves.
 */
@RestController
@RequestMapping("/api/whoami")
public class WhoAmIController {

    @GetMapping
    public WhoAmIResponse whoAmI(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        return new WhoAmIResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("azp"),
                jwt.getAudience(),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList(),
                jwt.getIssuedAt(),
                jwt.getExpiresAt());
    }
}
