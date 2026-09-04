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
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class NotificationOutboxAdminController {

    private static final Map<String, String> EVENT_TYPE_LABELS = Map.ofEntries(
            Map.entry("PAYMENT_APPROVED", "결제 완료"),
            Map.entry("PAYMENT_FAILED", "결제 실패"),
            Map.entry("PAYMENT_REVIEW_REQUIRED", "결제 확인 필요"),
            Map.entry("ORDER_BACKORDERED", "재고 부족"),
            Map.entry("STOCK_ALLOCATED", "재고 확보"),
            Map.entry("ORDER_CANCELLED", "주문 취소"),
            Map.entry("SHIPMENT_STARTED", "출고 시작"),
            Map.entry("DELIVERY_COMPLETED", "배송 완료"),
            Map.entry("RETURN_REQUESTED", "반품 요청"),
            Map.entry("RETURN_REJECTED", "반품 반려"),
            Map.entry("RETURN_RECEIVED", "반품 상품 입고"),
            Map.entry("RETURN_COMPLETED", "반품 처리 완료"),
            Map.entry("RETURN_CANCELLED", "반품 취소"),
            Map.entry("REFUND_COMPLETED", "환불 완료"),
            Map.entry("REFUND_REVIEW_REQUIRED", "환불 확인 필요"));

    private static final Map<String, String> ERROR_CODE_LABELS = Map.of(
            "IO_FAILURE", "통신 오류",
            "HTTP_422", "요청 처리 오류",
            "PROCESSING_TIMEOUT", "처리 시간 초과");

    private final NotificationOutboxService notificationOutboxService;

    @GetMapping("/admin/notification-events")
    public String notificationEvents(Model model) {
        model.addAttribute("failedEvents", notificationOutboxService.findFailed());
        model.addAttribute("eventTypeLabels", EVENT_TYPE_LABELS);
        model.addAttribute("errorCodeLabels", ERROR_CODE_LABELS);
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
