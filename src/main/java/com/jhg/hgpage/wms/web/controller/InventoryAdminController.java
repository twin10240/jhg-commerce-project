package com.jhg.hgpage.wms.web.controller;

import com.jhg.hgpage.wms.adapter.WmsInventoryAdapter;
import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter;
import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter.PurchaseOrderLine;
import com.jhg.hgpage.wms.web.form.PurchaseOrderForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class InventoryAdminController {

    private final WmsInventoryAdapter wmsInventoryAdapter;
    private final WmsInventoryQueryAdapter wmsInventoryQueryAdapter;
    private final WmsPurchaseOrderAdapter wmsPurchaseOrderAdapter;

    @GetMapping("/admin/inventory")
    public String inventory(Model model) {
        model.addAttribute("products", wmsInventoryQueryAdapter.allRows());
        return "admin/inventory";
    }

    @GetMapping("/admin/purchase-orders")
    public String purchaseOrders(Model model) {
        model.addAttribute("purchaseOrders", wmsPurchaseOrderAdapter.findAllWithItems());
        model.addAttribute("products", wmsInventoryQueryAdapter.allRows());
        return "admin/purchaseorders";
    }

    @PostMapping("/admin/inventory/adjust")
    public String adjustInventory(@RequestParam Long productId,
                                  @RequestParam int delta,
                                  @RequestParam(defaultValue = "") String reason,
                                  RedirectAttributes redirectAttributes) {
        try {
            // 승격 트리거는 WMS 콜백(POST /api/replenishments)이 담당 — S3에서 인프로세스 직접 호출 제거
            int adjusted = wmsInventoryAdapter.adjust(productId, delta, reason);
            redirectAttributes.addFlashAttribute("successMessage",
                    "재고가 조정되었습니다. (현재 " + adjusted + "개)");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }

    @PostMapping("/admin/purchase-orders")
    public String createPurchaseOrder(@ModelAttribute PurchaseOrderForm form,
                                      RedirectAttributes redirectAttributes) {
        List<PurchaseOrderLine> lines = form.getItems().stream()
                .map(item -> new PurchaseOrderLine(item.getProductId(), item.getQuantity()))
                .toList();
        try {
            Long poId = wmsPurchaseOrderAdapter.create(lines, form.getMemo());
            redirectAttributes.addFlashAttribute("successMessage",
                    "발주가 생성되었습니다. (발주 #" + poId + ")");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }

    @PostMapping("/admin/purchase-orders/receive")
    public String receivePurchaseOrder(@RequestParam Long poId,
                                       RedirectAttributes redirectAttributes) {
        try {
            wmsPurchaseOrderAdapter.receive(poId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "입고 처리되었습니다. (발주 #" + poId + ")");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }
}
