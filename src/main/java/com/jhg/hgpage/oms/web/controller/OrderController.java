package com.jhg.hgpage.oms.web.controller;

import com.jhg.hgpage.oms.web.form.CheckOutForm;
import com.jhg.hgpage.oms.web.form.OrderRequest;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.oms.repository.SearchOption;
import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.oms.service.OrderService;
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
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class OrderController {
    private final MemberService memberService;
    private final ProductRepository productRepository;
    private final OrderService orderService;

    @PostMapping("/orders/checkout-form")
    public String createCheckOutFrom(@AuthenticationPrincipal UserPrincipal user, @ModelAttribute OrderRequest req, Model model) {
        CheckOutForm checkOutForm = new CheckOutForm();

        // Fill checkout defaults from the authenticated user.
        if (user != null) {
            checkOutForm.getMember().setName(user.getUsername());
            checkOutForm.getMember().setEmail(user.getEmail());
            checkOutForm.getMember().setPhone(user.getPhone());
        }

        Member member = memberService.findMember(user.getId());
        checkOutForm.getDelivery().setCity(member.getAddress().getCity());
        checkOutForm.getDelivery().setStreet(member.getAddress().getStreet());
        checkOutForm.getDelivery().setZipcode(member.getAddress().getZipcode());
        checkOutForm.getDelivery().setSaveAsDefault(true);

        if (req.getItems().isEmpty()) {
            Product product = findProduct(req.getProductId());
            checkOutForm.getProduct().add(new CheckOutForm.ProductDto(req.getProductId(), product.getName(), product.getPrice(), req.getQty()));
        } else {
            checkOutForm.setFromCart(true);
            List<OrderRequest.OrderItem> selectedItems = req.getItems().stream()
                    .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                    .toList();

            // 라인별 findById(N+1) 대신 한 번의 findAllById로 일괄 조회한다(#9)
            List<Long> productIds = selectedItems.stream()
                    .map(OrderRequest.OrderItem::getProductId)
                    .toList();
            Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                    .collect(Collectors.toMap(Product::getId, Function.identity()));

            for (OrderRequest.OrderItem item : selectedItems) {
                Product product = products.get(item.getProductId());
                if (product == null) {
                    throw new EntityNotFoundException("Product", item.getProductId());
                }
                checkOutForm.getProduct().add(new CheckOutForm.ProductDto(
                        item.getProductId(),
                        product.getName(),
                        product.getPrice(),
                        item.getQty()
                ));
            }
        }

        model.addAttribute("checkout", checkOutForm);

        return "orderdetail";
    }

    @PostMapping("/orders/checkout")
    public String checkout(@AuthenticationPrincipal UserPrincipal user,
                           @Valid @ModelAttribute("checkout") CheckOutForm form,
                           BindingResult bindingResult) {
        // 체크된 상품만 주문 대상. 전부 해제하면 빈 주문과 동일하게 product 필드 에러로 안내한다.
        List<CheckOutForm.ProductDto> selectedProducts = form.getProduct().stream()
                .filter(CheckOutForm.ProductDto::isSelected)
                .toList();
        if (selectedProducts.isEmpty()) {
            bindingResult.rejectValue("product", "noneSelected", "주문할 상품을 1개 이상 선택해주세요.");
        }

        // 빈 주문 / 배송지 누락 / 수량 오류 시 주문서로 되돌리고 인라인 에러 표시
        if (bindingResult.hasErrors()) {
            restoreCheckOutDisplay(user, form);
            return "orderdetail";
        }

        Address deliveryAddress = new Address(
                form.getDelivery().getCity(),
                form.getDelivery().getStreet(),
                form.getDelivery().getZipcode()
        );

        List<OrderService.OrderLine> lines = selectedProducts.stream()
                .map(product -> new OrderService.OrderLine(product.getId(), product.getQuantity()))
                .toList();

        if (form.isFromCart()) {
            orderService.orderFromCart(user.getId(), deliveryAddress, lines);
        } else {
            orderService.order(user.getId(), deliveryAddress, lines);
        }

        return "redirect:/main";
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product", productId));
    }

    // 검증 실패로 주문서를 다시 렌더링할 때, 폼이 전송하지 않는 표시용 값(상품명/가격, disabled 회원정보)을 복원한다.
    private void restoreCheckOutDisplay(UserPrincipal user, CheckOutForm form) {
        if (user != null) {
            form.getMember().setName(user.getUsername());
            form.getMember().setEmail(user.getEmail());
        }

        // 라인별 findById(N+1) 대신 한 번의 findAllById로 일괄 조회한다(#19). 없는 상품은 그대로 둔다.
        List<Long> ids = form.getProduct().stream()
                .map(CheckOutForm.ProductDto::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, Product> products = productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        form.getProduct().forEach(item -> {
            Product product = products.get(item.getId()); // id가 null이면 get(null)→null로 자연히 건너뛴다
            if (product != null) {
                item.setName(product.getName());
                item.setPrice(product.getPrice());
            }
        });
    }

    // 새로고침은 JS가 가로채 /api/orders/me fetch로 처리. 이 매핑은 JS 비활성 시 폼 폴백. 검색 기능은 미구현.
    @GetMapping("/orders/me")
    public String findMyOrders(@AuthenticationPrincipal(expression = "id") Long userId, SearchOption searchOption, Model model) {
        return "redirect:/main";
    }

    // 본인 주문만 조회 가능 — 타인/없는 주문은 서비스가 404(EntityNotFoundException)로 숨긴다
    @GetMapping("/orders/{orderId}")
    public String orderDetail(@AuthenticationPrincipal UserPrincipal user,
                              @PathVariable Long orderId,
                              Model model) {
        model.addAttribute("order", orderService.findOrderDetail(orderId, user.getId()));
        return "orderview";
    }

    @PostMapping("/orders/{orderId}/cancel")
    public String cancelOrder(@AuthenticationPrincipal UserPrincipal user,
                              @PathVariable Long orderId,
                              RedirectAttributes redirectAttributes) {
        try {
            orderService.cancelOrder(orderId, user.getId());
            redirectAttributes.addFlashAttribute("successMessage", "주문이 취소되었습니다.");
        } catch (IllegalStateException e) {
            // 배송완료/이미취소 등 취소 불가 사유를 상세 화면에 flash로 안내
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/orders/" + orderId;
    }
}
