package com.jhg.hgpage.oms.web.controller;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.oms.dto.CustomerReturnDto;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.oms.service.ReturnSubmissionService;
import com.jhg.hgpage.oms.web.form.CustomerReturnForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class CustomerReturnController {

    private static final String BINDING_RESULT = "org.springframework.validation.BindingResult.returnForm";

    private final CustomerReturnService customerReturnService;
    private final ReturnSubmissionService returnSubmissionService;

    @PostMapping("/orders/{orderId}/returns")
    public String request(@AuthenticationPrincipal UserPrincipal user,
                          @PathVariable Long orderId,
                          @Valid @ModelAttribute("returnForm") CustomerReturnForm form,
                          BindingResult bindingResult,
                          RedirectAttributes redirectAttributes) {
        List<CustomerReturnService.ReturnLine> lines = form.getLines().stream()
                .filter(line -> line.getQuantity() > 0)
                .map(line -> new CustomerReturnService.ReturnLine(line.getOrderItemId(), line.getQuantity()))
                .toList();
        if (lines.isEmpty()) {
            bindingResult.rejectValue("lines", "noneSelected", "반품할 상품을 1개 이상 선택해주세요.");
        }

        if (!bindingResult.hasErrors()) {
            Long returnId = null;
            try {
                returnId = customerReturnService.request(orderId, user.getId(), form.getReason(), lines);
            } catch (IllegalArgumentException exception) {
                bindingResult.reject("invalidReturn", exception.getMessage());
            }
            if (returnId != null) {
                returnSubmissionService.submit(returnId);
                redirectAttributes.addFlashAttribute("successMessage", "반품 요청이 저장되었습니다.");
            }
        }

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("returnForm", form);
            redirectAttributes.addFlashAttribute(BINDING_RESULT, bindingResult);
        }
        return "redirect:/orders/" + orderId;
    }

    @GetMapping("/returns/{returnId}")
    public String detail(@AuthenticationPrincipal UserPrincipal user,
                         @PathVariable Long returnId,
                         Model model) {
        model.addAttribute("customerReturn", CustomerReturnDto.from(
                customerReturnService.findOwned(returnId, user.getId())));
        return "returnview";
    }
}
