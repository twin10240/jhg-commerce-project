package com.jhg.hgpage.realtime.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(annotations = Controller.class)
public class RealtimeViewAdvice {

    private final String realtimePublicUrl;

    public RealtimeViewAdvice(@Value("${realtime.public-url:http://localhost:3000}") String realtimePublicUrl) {
        this.realtimePublicUrl = normalize(realtimePublicUrl);
    }

    @ModelAttribute
    void addRealtimePublicUrl(Model model, Authentication authentication) {
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_USER"))) {
            model.addAttribute("realtimePublicUrl", realtimePublicUrl);
        }
    }

    private static String normalize(String url) {
        int schemeEnd = url.indexOf("://");
        int minimumLength = schemeEnd >= 0 ? schemeEnd + 3 : 1;
        int end = url.length();
        while (end > minimumLength && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
