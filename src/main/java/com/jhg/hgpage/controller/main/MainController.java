package com.jhg.hgpage.controller.main;

import com.jhg.hgpage.domain.dto.view.OrderDto;
import com.jhg.hgpage.catalog.ProductCardDto;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.repository.SearchOption;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import com.jhg.hgpage.catalog.ProductService;
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
                        @PageableDefault(size = 10, sort = "id") Pageable pageable,
                        Model model) {
        // 사용자 상품 그리드: 검색 + 페이징 적용. 가용수량은 카드 DTO에 담겨 온다(재고 객체그래프 비노출)
        Page<ProductCardDto> productPage = productService.findCardPage(keyword, pageable);
        model.addAttribute("productPage", productPage);
        model.addAttribute("keyword", keyword);

        // 숫자 페이지 네비게이션: 현재 페이지를 중심으로 최대 5개 번호를 노출한다. (0-based)
        int totalPages = productPage.getTotalPages();
        int beginPage = Math.max(0, Math.min(productPage.getNumber() - 2, totalPages - 5));
        int endPage = Math.min(Math.max(totalPages - 1, 0), beginPage + 4);
        model.addAttribute("beginPage", beginPage);
        model.addAttribute("endPage", endPage);

        // 관리자 재고/발주 select 전용: ADMIN 일 때만 전체 상품을 조회한다.
        // (일반 사용자는 이 데이터를 렌더링하지 않으므로 불필요한 전체 스캔을 피한다)
        boolean isAdmin = userPrincipal.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (isAdmin) {
            model.addAttribute("inventoryProducts", productService.findAllWithInventory());
        }

        List<OrderDto> orders = orderService.findOrders(userPrincipal.getId());
        model.addAttribute("orders", orders);

        model.addAttribute("role", userPrincipal.getAuthorities());

        return "main";
    }
}
