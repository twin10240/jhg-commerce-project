package com.jhg.hgpage.realtime.web;

import com.jhg.hgpage.realtime.outbox.NotificationOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class NotificationOutboxAdminController {

    private final NotificationOutboxService notificationOutboxService;

    @GetMapping("/admin/notification-events")
    public String notificationEvents(Model model) {
        model.addAttribute("failedEvents", notificationOutboxService.findFailed());
        return "admin/notification-events";
    }

    @PostMapping("/admin/notification-events/{id}/retry")
    public String retry(@PathVariable String id, RedirectAttributes redirectAttributes) {
        UUID eventId;
        try {
            eventId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "알림 이벤트 ID가 올바르지 않습니다.");
            return "redirect:/admin/notification-events";
        }

        Instant now = Instant.now();
        if (notificationOutboxService.requeueFailed(eventId, now)) {
            redirectAttributes.addFlashAttribute("successMessage", "알림 이벤트를 재전송 대기 상태로 변경했습니다.");
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", "재전송할 수 없는 알림 이벤트입니다.");
        }
        return "redirect:/admin/notification-events";
    }
}
