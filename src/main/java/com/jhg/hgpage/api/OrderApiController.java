package com.jhg.hgpage.api;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.dto.view.OrderDto;
import com.jhg.hgpage.oms.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderApiController {

    private final OrderService orderService;

    // 메인 "내 주문" 새로고침 버튼의 fetch 호출용
    @GetMapping("/me")
    public List<OrderDto> findMyOrders(@AuthenticationPrincipal UserPrincipal user) {
        return orderService.findOrders(user.getId());
    }
}
