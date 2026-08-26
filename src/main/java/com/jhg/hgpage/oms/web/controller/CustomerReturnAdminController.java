package com.jhg.hgpage.oms.web.controller;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.oms.service.ReturnSubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@Slf4j
public class CustomerReturnAdminController {

    private final CustomerReturnService customerReturnService;
    private final ReturnSubmissionService returnSubmissionService;

    @GetMapping("/admin/returns")
    public String returns(@RequestParam(required = false) CustomerReturnStatus status, Model model) {
        model.addAttribute("returns", customerReturnService.findAllForAdmin(status));
        model.addAttribute("statuses", CustomerReturnStatus.values());
        model.addAttribute("activeStatus", status);
        return "admin/returns";
    }

    @PostMapping("/admin/returns/{returnId}/approve")
    public String approve(@AuthenticationPrincipal UserPrincipal admin,
                          @PathVariable Long returnId,
                          RedirectAttributes redirectAttributes) {
        try {
            customerReturnService.approveReturn(returnId, admin.getEmail());
            try {
                returnSubmissionService.submit(returnId);
                redirectAttributes.addFlashAttribute("successMessage", "반품을 승인했습니다.");
            } catch (RuntimeException exception) {
                log.warn("승인 후 WMS 반품 전송 실패: returnId={}", returnId, exception);
                redirectAttributes.addFlashAttribute("successMessage", "승인은 완료되었으며 WMS 전송을 다시 확인합니다.");
            }
        } catch (IllegalArgumentException | IllegalStateException | EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:/admin/returns";
    }

    @PostMapping("/admin/returns/{returnId}/reject")
    public String reject(@AuthenticationPrincipal UserPrincipal admin,
                         @PathVariable Long returnId,
                         @RequestParam String reason,
                         RedirectAttributes redirectAttributes) {
        try {
            customerReturnService.rejectReturn(returnId, admin.getEmail(), reason);
            redirectAttributes.addFlashAttribute("successMessage", "반품을 반려했습니다.");
        } catch (IllegalArgumentException | IllegalStateException | EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:/admin/returns";
    }
}
