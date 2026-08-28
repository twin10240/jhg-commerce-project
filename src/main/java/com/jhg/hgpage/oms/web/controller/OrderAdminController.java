package com.jhg.hgpage.oms.web.controller;

import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.service.PaymentAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
@Slf4j
public class OrderAdminController {

    private final OrderService orderService;
    private final PaymentAdminService paymentAdminService;

    @GetMapping("/admin/orders")
    public String orders(@RequestParam(defaultValue = "all") String filter, Model model) {
        String selectedFilter = List.of("all", "ready", "shipping", "backorder", "completed").contains(filter)
                ? filter : "all";
        var allOrders = orderService.findAllForAdmin();
        Map<String, Long> counts = allOrders.stream()
                .collect(Collectors.groupingBy(order -> order.getManagementGroup(), Collectors.counting()));
        model.addAttribute("orders", "all".equals(selectedFilter) ? allOrders : allOrders.stream()
                .filter(order -> selectedFilter.equals(order.getManagementGroup()))
                .toList());
        model.addAttribute("filter", selectedFilter);
        model.addAttribute("orderCounts", counts);
        return "admin/orders";
    }

    // HTML 폼 제약 때문에 path variable 대신 orderId 파라미터를 받는다 (발주 입고와 동일 패턴)
    @PostMapping("/admin/orders/ship")
    public String ship(@RequestParam Long orderId, RedirectAttributes redirectAttributes) {
        try {
            orderService.shipOrder(orderId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "출고 처리되었습니다. (주문 #" + orderId + ")");
        } catch (IllegalStateException | EntityNotFoundException | RestClientException e) {
            redirectAttributes.addFlashAttribute("errorMessage", shipmentFailure(orderId, e));
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
        List<String> failures = new ArrayList<>();
        for (Long orderId : orderIds.stream().distinct().toList()) {
            try {
                orderService.shipOrder(orderId);
                successCount++;
            } catch (IllegalStateException | EntityNotFoundException | RestClientException e) {
                failures.add(shipmentFailure(orderId, e));
            }
        }

        int failureCount = failures.size();
        String message = "출고 처리 결과: 성공 " + successCount + "건 / 실패 " + failureCount + "건.";
        if (!failures.isEmpty()) {
            message += " 실패 사유: " + String.join(" / ", failures);
        }
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

    @PostMapping("/admin/orders/{orderId}/shipment/sync")
    public String syncShipment(@PathVariable Long orderId, RedirectAttributes redirectAttributes) {
        try {
            orderService.syncShipment(orderId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "WMS 송장을 동기화했습니다. (주문 #" + orderId + ")");
        } catch (IllegalStateException | EntityNotFoundException | RestClientException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "송장 동기화 실패: " + e.getMessage());
        }
        return "redirect:/admin/orders";
    }

    @PostMapping("/admin/orders/{orderId}/allocation/retry")
    public String retryAllocation(@PathVariable Long orderId,
                                  RedirectAttributes redirectAttributes) {
        paymentAdminService.retryAllocation(orderId);
        redirectAttributes.addFlashAttribute("successMessage", "재고 처리를 다시 요청했습니다.");
        return "redirect:/admin/orders";
    }

    private String shipmentFailure(Long orderId, RuntimeException exception) {
        String detail;
        if (exception instanceof ResourceAccessException) {
            detail = "[WMS_UNAVAILABLE] WMS와 통신할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        } else if (exception instanceof RestClientResponseException response) {
            String body = response.getResponseBodyAsString();
            if (response.getStatusCode().value() == 409 && body.contains("예약이 없어")) {
                detail = "[WMS_RESERVATION_NOT_FOUND] WMS 예약 내역이 없습니다. OMS/WMS 동기화 상태를 확인해 주세요.";
            } else {
                detail = "[WMS_REQUEST_REJECTED] WMS가 출고 요청을 거부했습니다. (HTTP "
                        + response.getStatusCode().value() + ")";
            }
        } else if (exception instanceof EntityNotFoundException) {
            detail = "[ORDER_NOT_FOUND] 주문 정보를 찾을 수 없습니다.";
        } else {
            detail = "[INVALID_ORDER_STATE] " + exception.getMessage();
        }
        log.warn("Shipment failed: orderId={}, detail={}", orderId, detail, exception);
        return "주문 #" + orderId + " — " + detail;
    }
}
