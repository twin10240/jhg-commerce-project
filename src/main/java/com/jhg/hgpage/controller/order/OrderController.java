package com.jhg.hgpage.controller.order;

import com.jhg.hgpage.controller.form.CheckOutForm;
import com.jhg.hgpage.controller.form.OrderRequest;
import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.ProductRepository;
import com.jhg.hgpage.repository.SearchOption;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class OrderController {
    private final MemberService memberService;
    private final ProductRepository productRepository;
    private final OrderService orderService;

//    @PostMapping("/orders")
//    public String createOrder(@AuthenticationPrincipal(expression = "id") Long userId, @ModelAttribute OrderRequest req) {
//        if (req.getItems().isEmpty()) {
//
//        }
//
//        orderService.order(userId, product_id, quantity);
//
//        return "redirect:/main";
//    }

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
            List<OrderRequest.OrderItem> selectedItems = req.getItems().stream()
                    .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                    .toList();

            for (OrderRequest.OrderItem item : selectedItems) {
                Product product = findProduct(item.getProductId());
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

        orderService.order(user.getId(), deliveryAddress, lines);

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

        form.getProduct().forEach(item -> {
            if (item.getId() == null) {
                return;
            }
            productRepository.findById(item.getId()).ifPresent(product -> {
                item.setName(product.getName());
                item.setPrice(product.getPrice());
            });
        });
    }

    @GetMapping("/orders/me")
    public String findMyOrders(@AuthenticationPrincipal(expression = "id") Long userId, SearchOption searchOption, Model model) {
//        List<Order> orders = orderService.findOrders(searchOption, userId);
//        List<OrderDto> result = orders.stream().map(o -> new OrderDto(o.getId(), o.getStatus(), o.getTotalPrice(), o.getOrderDate())).toList();

//        model.addAttribute("orders", result);

        return "redirect:/main";
    }
}
