package com.jhg.hgpage.wms.web.controller;

import com.jhg.hgpage.catalog.ProductService;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.service.InventoryAdjustmentService;
import com.jhg.hgpage.wms.service.PurchaseOrderService;
import com.jhg.hgpage.wms.service.PurchaseOrderService.PurchaseOrderLine;
import com.jhg.hgpage.wms.web.form.PurchaseOrderForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class InventoryAdminController {

    private final InventoryAdjustmentService inventoryAdjustmentService;
    private final ProductService productService;
    private final PurchaseOrderService purchaseOrderService;

    @GetMapping("/admin/inventory")
    public String inventory(Model model) {
        // 재고 화면: 재고 현황 조회 + 재고 조정. 발주는 /admin/purchase-orders 로 분리.
        model.addAttribute("products", productService.findAllWithInventory());
        return "admin/inventory";
    }

    @GetMapping("/admin/purchase-orders")
    public String purchaseOrders(Model model) {
        // 발주 화면: 발주 생성 + 발주 현황 + 입고.
        model.addAttribute("purchaseOrders", purchaseOrderService.findAllWithItems());
        model.addAttribute("products", productService.findAllWithInventory()); // 발주 생성 select용
        return "admin/purchaseorders";
    }

    @PostMapping("/admin/inventory/adjust")
    public String adjustInventory(@RequestParam Long productId,
                                  @RequestParam int delta,
                                  @RequestParam(defaultValue = "") String reason,
                                  RedirectAttributes redirectAttributes) {
        try {
            int adjusted = inventoryAdjustmentService.adjust(productId, delta, reason);
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
            Long poId = purchaseOrderService.create(lines, form.getMemo());
            redirectAttributes.addFlashAttribute("successMessage",
                    "발주가 생성되었습니다. (발주 #" + poId + ")");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }

    // HTML 폼은 입력값을 path에 넣을 수 없으므로 path variable 대신 poId 파라미터를 받는다
    @PostMapping("/admin/purchase-orders/receive")
    public String receivePurchaseOrder(@RequestParam Long poId,
                                       RedirectAttributes redirectAttributes) {
        try {
            Long receivedId = purchaseOrderService.receive(poId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "입고 처리되었습니다. (발주 #" + receivedId + ")");
        } catch (IllegalStateException | EntityNotFoundException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }
}
