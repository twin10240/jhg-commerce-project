package com.jhg.hgpage.realtime.chat;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@Controller
@ConditionalOnProperty(name = "realtime.chat.enabled", havingValue = "true")
public class ChatPageController {
    @GetMapping("/chat")
    public String customer(@RequestParam Long orderId,
                           @RequestParam(required = false) UUID conversationId,
                           Model model) {
        model.addAttribute("orderId", orderId);
        model.addAttribute("conversationId", conversationId);
        return "chat";
    }

    @GetMapping("/admin/chat")
    public String admin(@RequestParam(required = false) UUID conversationId, Model model) {
        model.addAttribute("conversationId", conversationId);
        return "admin/chat";
    }

    @GetMapping("/chat/conversations/{conversationId}")
    public String notificationLink(@AuthenticationPrincipal UserPrincipal principal,
                                   @RequestParam Long orderId,
                                   @org.springframework.web.bind.annotation.PathVariable UUID conversationId) {
        if (principal.getRole() == Role.ADMIN) return "redirect:/admin/chat?conversationId=" + conversationId;
        return "redirect:/chat?orderId=" + orderId + "&conversationId=" + conversationId;
    }
}
