package com.jhg.hgpage.wms.web.controller;

import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import com.jhg.hgpage.wms.adapter.WmsReplenishmentRequestAdapter;
import com.jhg.hgpage.wms.adapter.WmsReplenishmentRequestAdapter.RequestLine;
import com.jhg.hgpage.wms.web.form.ReplenishmentRequestForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class InventoryAdminController {

    private final WmsInventoryQueryAdapter wmsInventoryQueryAdapter;
    private final WmsReplenishmentRequestAdapter requestAdapter;

    @GetMapping("/admin/inventory")
    public String inventory(Model model) {
        model.addAttribute("products", wmsInventoryQueryAdapter.allRows());
        model.addAttribute("requests", requestAdapter.findAll());
        if (!model.containsAttribute("requestForm")) {
            var form = new ReplenishmentRequestForm();
            form.setRequestKey(UUID.randomUUID());
            model.addAttribute("requestForm", form);
        }
        return "admin/inventory";
    }

    @PostMapping("/admin/replenishment-requests")
    public String createRequest(@ModelAttribute ReplenishmentRequestForm requestForm,
                                RedirectAttributes redirectAttributes) {
        var lines = requestForm.getItems().stream()
                .map(item -> new RequestLine(item.getProductId(), item.getRequestedQty()))
                .toList();
        try {
            requestAdapter.create(requestForm.getRequestKey(), lines, requestForm.getReason());
            redirectAttributes.addFlashAttribute("successMessage", "Replenishment request submitted.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            redirectAttributes.addFlashAttribute("requestForm", requestForm);
        }
        return "redirect:/admin/inventory";
    }
}
