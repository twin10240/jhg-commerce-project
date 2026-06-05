package com.jhg.hgpage.controller.main;

import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.view.OrderDto;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.repository.SearchOption;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import com.jhg.hgpage.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class MainController {
    private final MemberService memberService;
    private final ProductService productService;
    private final OrderService orderService;

    @GetMapping("/main")
    public String logIn(@AuthenticationPrincipal UserPrincipal userPrincipal,
                        @ModelAttribute("searchOption") SearchOption searchOption,
                        @RequestParam(defaultValue = "") String keyword,
                        @PageableDefault(size = 12, sort = "id") Pageable pageable,
                        Model model) {
        // 사용자 상품 그리드: 검색 + 페이징 적용
        Page<Product> productPage = productService.findPage(keyword, pageable);
        model.addAttribute("productPage", productPage);
        model.addAttribute("keyword", keyword);

        // 관리자 재고/발주 select 전용: ADMIN 일 때만 전체 상품을 조회한다.
        // (일반 사용자는 이 데이터를 렌더링하지 않으므로 불필요한 전체 스캔을 피한다)
        boolean isAdmin = userPrincipal.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (isAdmin) {
            model.addAttribute("inventoryProducts", productService.findAll());
        }

        List<OrderDto> orders = orderService.findOrders(userPrincipal.getId());
        model.addAttribute("orders", orders);

        model.addAttribute("role", userPrincipal.getAuthorities());

        return "main";
    }
}
