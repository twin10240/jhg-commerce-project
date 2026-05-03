package com.jhg.hgpage.controller.order;

import com.jhg.hgpage.controller.form.CheckOutForm;
import com.jhg.hgpage.controller.form.OrderRequest;
import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.repository.ProductRepository;
import com.jhg.hgpage.repository.SearchOption;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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

        Member member = memberService.findById(user.getId());
        checkOutForm.getDelivery().setCity(member.getAddress().getCity());
        checkOutForm.getDelivery().setStreet(member.getAddress().getStreet());
        checkOutForm.getDelivery().setZipcode(member.getAddress().getZipcode());
        checkOutForm.getDelivery().setSaveAsDefault(true);

        if (req.getItems().isEmpty()) {
            Product product = productRepository.findById(req.getProductId()).get();
            checkOutForm.getProduct().add(new CheckOutForm.ProductDto(req.getProductId(), product.getName(), product.getPrice(), req.getQty()));
        } else {
            List<OrderRequest.OrderItem> selectedItems = req.getItems().stream()
                    .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                    .toList();

            for (OrderRequest.OrderItem item : selectedItems) {
                Product product = productRepository.findById(item.getProductId()).get();
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
    public String checkout(@AuthenticationPrincipal UserPrincipal user, @ModelAttribute("checkout") CheckOutForm form) {
        Address deliveryAddress = new Address(
                form.getDelivery().getCity(),
                form.getDelivery().getStreet(),
                form.getDelivery().getZipcode()
        );

        List<OrderService.OrderLine> lines = form.getProduct().stream()
                .map(product -> new OrderService.OrderLine(product.getId(), product.getQuantity()))
                .toList();

        orderService.order(user.getId(), deliveryAddress, lines);

        return "redirect:/main";
    }

    @GetMapping("/orders/me")
    public String findMyOrders(@AuthenticationPrincipal(expression = "id") Long userId, SearchOption searchOption, Model model) {
//        List<Order> orders = orderService.findOrders(searchOption, userId);
//        List<OrderDto> result = orders.stream().map(o -> new OrderDto(o.getId(), o.getStatus(), o.getTotalPrice(), o.getOrderDate())).toList();

//        model.addAttribute("orders", result);

        return "redirect:/main";
    }
}
