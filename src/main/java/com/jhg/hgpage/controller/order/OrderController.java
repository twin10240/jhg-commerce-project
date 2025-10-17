package com.jhg.hgpage.controller.order;

import com.jhg.hgpage.controller.form.CheckOutForm;
import com.jhg.hgpage.controller.form.OrderRequest;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.repositoey.ProductRepository;
import com.jhg.hgpage.repositoey.SearchOption;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

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
////        orderService.order(userId, product_id, quantity);
//
//        return "redirect:/main";
//    }

    @PostMapping("/orders")
    public String createCheckOutFrom(@AuthenticationPrincipal UserPrincipal user, @ModelAttribute OrderRequest req, Model model) {
        CheckOutForm checkOutForm = new CheckOutForm();

        // 로그인 사용자 정보로 기본값 채우기 (서버 신뢰 값)
        if (user != null) {
            checkOutForm.getMember().setName(user.getUsername());
            checkOutForm.getMember().setEmail(user.getEmail());
            checkOutForm.getMember().setPhone(user.getPhone());
        }

        Member member = memberService.findById(user.getId());
        checkOutForm.getDelivery().setCity(member.getAddress().getCity());
        checkOutForm.getDelivery().setStreet(member.getAddress().getStreet());
        checkOutForm.getDelivery().setCity(member.getAddress().getCity());
        checkOutForm.getDelivery().setSaveAsDefault(true);

        if (req.getItems().isEmpty()) {
            Product product = productRepository.findById(req.getProductId()).get();
            checkOutForm.getProduct().add(new CheckOutForm.ProductDto(req.getProductId(), product.getName(), product.getPrice(), req.getQty()));
        } else {

        }

        model.addAttribute("checkout", checkOutForm);

        return "orderdetail";
    }

    @GetMapping("/orders/me")
    public String findMyOrders(@AuthenticationPrincipal(expression = "id") Long userId, SearchOption searchOption, Model model) {
//        List<Order> orders = orderService.findOrders(searchOption, userId);
//        List<OrderDto> result = orders.stream().map(o -> new OrderDto(o.getId(), o.getStatus(), o.getTotalPrice(), o.getOrderDate())).toList();

//        model.addAttribute("orders", result);

        return "redirect:/main";
    }
}
