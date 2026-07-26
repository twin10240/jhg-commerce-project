package com.jhg.hgpage.wms.web.controller;

import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import com.jhg.hgpage.wms.adapter.WmsReplenishmentRequestAdapter;
import com.jhg.hgpage.wms.adapter.WmsReplenishmentRequestAdapter.RequestLine;
import com.jhg.hgpage.wms.web.form.ReplenishmentRequestForm;
import com.jhg.hgpage.oms.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class InventoryAdminController {

    private final WmsInventoryQueryAdapter wmsInventoryQueryAdapter;
    private final WmsReplenishmentRequestAdapter requestAdapter;
    private final OrderService orderService;

    @GetMapping("/admin/inventory")
    public String inventory(Model model) {
        List<com.jhg.hgpage.wms.dto.InventoryRow> products;
        try {
            products = wmsInventoryQueryAdapter.allRows();
        } catch (ResourceAccessException exception) {
            model.addAttribute("inventoryUnavailable", true);
            model.addAttribute("products", List.of());
            model.addAttribute("backorderDemand", Map.of());
            model.addAttribute("pendingRequests", Map.of());
            return "admin/inventory";
        }
        var demand = orderService.backorderDemandByProductId(
                products.stream().map(product -> product.productId()).toList());
        var availableByProductId = products.stream().collect(Collectors.toMap(
                product -> product.productId(), product -> product.availableQty()));
        int backorderQty = demand.values().stream().mapToInt(Integer::intValue).sum();
        int inboundRequiredQty = demand.entrySet().stream()
                .mapToInt(entry -> Math.max(entry.getValue() - availableByProductId.getOrDefault(entry.getKey(), 0), 0))
                .sum();
        model.addAttribute("products", products);
        model.addAttribute("backorderDemand", demand);
        model.addAttribute("soldOutCount", products.stream().filter(product -> product.availableQty() == 0).count());
        model.addAttribute("lowStockCount", products.stream()
                .filter(product -> product.availableQty() > 0 && product.availableQty() < 10).count());
        model.addAttribute("backorderQty", backorderQty);
        model.addAttribute("inboundRequiredQty", inboundRequiredQty);
        model.addAttribute("allocationWaitingQty", backorderQty - inboundRequiredQty);
        model.addAttribute("pendingRequests", requestAdapter.findAll().stream()
                .filter(request -> !"REJECTED".equals(request.status()) && !"FULFILLED".equals(request.status()))
                .flatMap(request -> request.items().stream().map(item -> Map.entry(item.productId(), request.id())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, ignored) -> first)));
        return "admin/inventory";
    }

    @GetMapping("/admin/replenishment-requests")
    public String replenishmentRequests(@RequestParam(required = false) Long productId, Model model) {
        var products = wmsInventoryQueryAdapter.allRows();
        model.addAttribute("products", products);
        model.addAttribute("productNames", products.stream().collect(Collectors.toMap(
                product -> product.productId(), product -> product.productName())));
        var requests = requestAdapter.findAll();
        model.addAttribute("requests", requests);
        model.addAttribute("pendingRequestCount", requests.stream()
                .filter(request -> !"REJECTED".equals(request.status()) && !"FULFILLED".equals(request.status()))
                .count());
        if (!model.containsAttribute("requestForm")) {
            var form = new ReplenishmentRequestForm();
            form.setRequestKey(UUID.randomUUID());
            if (productId != null) {
                form.getItems().get(0).setProductId(productId);
            }
            model.addAttribute("requestForm", form);
        }
        return "admin/replenishment-requests";
    }

    @PostMapping("/admin/replenishment-requests")
    public String createRequest(@ModelAttribute ReplenishmentRequestForm requestForm,
                                RedirectAttributes redirectAttributes) {
        var lines = requestForm.getItems().stream()
                .map(item -> new RequestLine(item.getProductId(), item.getRequestedQty()))
                .toList();
        try {
            requestAdapter.create(requestForm.getRequestKey(), lines, requestForm.getReason());
            redirectAttributes.addFlashAttribute(
                    "successMessage", "재고 보충 요청이 WMS에 접수되었습니다.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            redirectAttributes.addFlashAttribute("requestForm", requestForm);
        }
        return "redirect:/admin/replenishment-requests";
    }
}
