package com.jhg.hgpage.realtime.web;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.realtime.auth.RealtimeTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class RealtimeTokenApiController {
    private final RealtimeTokenService tokenService;

    public RealtimeTokenApiController(RealtimeTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/api/realtime/token")
    public RealtimeTokenService.TokenResponse issue(@AuthenticationPrincipal UserPrincipal principal) {
        return tokenService.issue(principal, Instant.now());
    }

    @ExceptionHandler(RealtimeTokenService.TokenUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ProblemDetail unavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Realtime token service is unavailable");
    }
}
