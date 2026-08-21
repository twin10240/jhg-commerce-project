package com.jhg.hgpage.oms.web.controller;

import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.service.PaymentAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderService orderService;
    private final PaymentAdminService paymentAdminService;

    @GetMapping("/admin/orders")
    public String orders(Model model) {
        model.addAttribute("orders", orderService.findAllForAdmin());
        return "admin/orders";
    }

    // HTML 폼 제약 때문에 path variable 대신 orderId 파라미터를 받는다 (발주 입고와 동일 패턴)
    @PostMapping("/admin/orders/ship")
    public String ship(@RequestParam Long orderId, RedirectAttributes redirectAttributes) {
        try {
            orderService.shipOrder(orderId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "출고 처리되었습니다. (주문 #" + orderId + ")");
        } catch (IllegalStateException | EntityNotFoundException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/orders";
    }

    @PostMapping("/admin/orders/ships")
    public String ships(@RequestParam(required = false) List<Long> orderIds,
                        RedirectAttributes redirectAttributes) {
        if (orderIds == null || orderIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "출고할 주문을 선택해주세요.");
            return "redirect:/admin/orders";
        }

        int successCount = 0;
        int failureCount = 0;
        for (Long orderId : orderIds.stream().distinct().toList()) {
            try {
                orderService.shipOrder(orderId);
                successCount++;
            } catch (IllegalStateException | EntityNotFoundException | RestClientException e) {
                failureCount++;
            }
        }

        String message = "출고 처리 결과: 성공 " + successCount + "건 / 실패 " + failureCount + "건.";
        redirectAttributes.addFlashAttribute(
                failureCount == 0 ? "successMessage" : "errorMessage", message);
        return "redirect:/admin/orders";
    }

    @PostMapping("/admin/orders/deliver")
    public String deliver(@RequestParam Long orderId, RedirectAttributes redirectAttributes) {
        try {
            orderService.deliverOrder(orderId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "배송 완료되었습니다. (주문 #" + orderId + ")");
        } catch (IllegalStateException | EntityNotFoundException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/orders";
    }

    @PostMapping("/admin/orders/{orderId}/allocation/retry")
    public String retryAllocation(@org.springframework.web.bind.annotation.PathVariable Long orderId,
                                  RedirectAttributes redirectAttributes) {
        paymentAdminService.retryAllocation(orderId);
        redirectAttributes.addFlashAttribute("successMessage", "재고 처리를 다시 요청했습니다.");
        return "redirect:/admin/orders";
    }
}
