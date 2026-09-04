package com.jhg.hgpage.realtime.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@ConditionalOnProperty(name = "realtime.chat.enabled", havingValue = "true")
public class ChatPageController {
    @GetMapping("/chat")
    public String customer(@RequestParam Long orderId, Model model) {
        model.addAttribute("orderId", orderId);
        return "chat";
    }

    @GetMapping("/admin/chat")
    public String admin() {
        return "admin/chat";
    }
}
