package com.jhg.hgpage.oms.web.controller;

import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.service.PaymentAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class PaymentAdminController {

    private final PaymentAdminService paymentAdminService;

    @GetMapping("/admin/payments")
    public String payments(@RequestParam(defaultValue = "payment") String tab,
                           @RequestParam(required = false) PaymentStatus paymentStatus,
                           @RequestParam(required = false) RefundStatus refundStatus,
                           Model model) {
        String activeTab = "refund".equals(tab) ? "refund" : "payment";
        boolean refundTab = "refund".equals(activeTab);
        paymentStatus = refundTab ? null : paymentStatus;
        refundStatus = refundTab ? refundStatus : null;
        PaymentAdminService.PageView page = paymentAdminService.findPage(refundTab, paymentStatus, refundStatus);
        model.addAttribute("payments", page.payments());
        model.addAttribute("refunds", page.refunds());
        model.addAttribute("counts", page.counts());
        model.addAttribute("paymentStatus", paymentStatus);
        model.addAttribute("refundStatus", refundStatus);
        model.addAttribute("paymentStatuses", PaymentStatus.values());
        model.addAttribute("refundStatuses", RefundStatus.values());
        model.addAttribute("activeTab", activeTab);
        return "admin/payments";
    }

    @PostMapping("/admin/refunds/{refundId}/retry")
    public String retryRefund(@PathVariable Long refundId, RedirectAttributes redirectAttributes) {
        paymentAdminService.retryRefund(refundId);
        redirectAttributes.addFlashAttribute("successMessage", "환불 처리를 다시 요청했습니다.");
        return "redirect:/admin/payments?tab=refund";
    }

    @PostMapping("/admin/payment-attempts/{attemptId}/retry")
    public String retryCancellationPayment(@PathVariable Long attemptId,
                                           RedirectAttributes redirectAttributes) {
        paymentAdminService.retryCancellationPayment(attemptId);
        redirectAttributes.addFlashAttribute("successMessage", "결제 확인을 다시 요청했습니다.");
        return "redirect:/admin/payments?tab=payment";
    }
}
