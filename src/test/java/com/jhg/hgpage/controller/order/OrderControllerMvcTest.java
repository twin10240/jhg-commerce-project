package com.jhg.hgpage.controller.order;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.exception.NotEnoughStockException;
import com.jhg.hgpage.repository.ProductRepository;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 컨트롤러 슬라이스 통합 테스트.
 * 단위 테스트(OrderControllerTest)와 달리 실제 @Valid 바인딩 + 시큐리티 + 뷰/모델 처리를 거쳐
 * /orders/checkout 의 엔드투엔드 분기를 검증한다.
 */
@WebMvcTest(OrderController.class)
class OrderControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean MemberService memberService;
    @MockBean ProductRepository productRepository;
    @MockBean OrderService orderService;

    private UserPrincipal principal() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    @Test
    void 빈주문이면_주문하지않고_orderdetail로_돌아간다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"))
                .andExpect(model().attributeHasFieldErrors("checkout", "product"));

        verify(orderService, never()).order(anyLong(), any(Address.class), anyList());
    }

    @Test
    void 수량이_0이면_orderdetail로_돌아간다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "0"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"))
                .andExpect(model().attributeHasFieldErrors("checkout", "product[0].quantity"));

        verify(orderService, never()).order(anyLong(), any(Address.class), anyList());
    }

    @Test
    void 정상주문이면_주문하고_main으로_리다이렉트한다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"));

        verify(orderService).order(eq(1L), any(Address.class), anyList());
    }

    @Test
    void 선택된_상품만_주문된다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2")
                        .param("product[0].selected", "true")
                        .param("product[1].id", "2")
                        .param("product[1].quantity", "3")
                        .param("product[1].selected", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderService.OrderLine>> linesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(orderService).order(eq(1L), any(Address.class), linesCaptor.capture());

        List<OrderService.OrderLine> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).productId()).isEqualTo(1L);
        assertThat(lines.get(0).quantity()).isEqualTo(2);
    }

    @Test
    void 아무것도_선택하지_않으면_주문하지않고_orderdetail로_돌아간다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2")
                        .param("product[0].selected", "false")
                        .param("product[1].id", "2")
                        .param("product[1].quantity", "3")
                        .param("product[1].selected", "false"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"))
                .andExpect(model().attributeHasFieldErrors("checkout", "product"));

        verify(orderService, never()).order(anyLong(), any(Address.class), anyList());
    }

    @Test
    void 재고가_부족하면_main으로_리다이렉트하고_에러메시지를_flash에_담는다() throws Exception {
        doThrow(new NotEnoughStockException("need more stock"))
                .when(orderService).order(anyLong(), any(Address.class), anyList());

        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 재고_수정이_충돌하면_main으로_리다이렉트하고_에러메시지를_flash에_담는다() throws Exception {
        doThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .when(orderService).order(anyLong(), any(Address.class), anyList());

        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 장바구니발_주문이면_orderFromCart로_체크된_상품만_주문한다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("fromCart", "true")
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2")
                        .param("product[0].selected", "true")
                        .param("product[1].id", "2")
                        .param("product[1].quantity", "3")
                        .param("product[1].selected", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderService.OrderLine>> linesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(orderService).orderFromCart(eq(1L), any(Address.class), linesCaptor.capture());
        verify(orderService, never()).order(anyLong(), any(Address.class), anyList());

        List<OrderService.OrderLine> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).productId()).isEqualTo(1L);
    }

    @Test
    void 바로구매_주문이면_장바구니_정리없이_주문한다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"));

        verify(orderService).order(eq(1L), any(Address.class), anyList());
        verify(orderService, never()).orderFromCart(anyLong(), any(Address.class), anyList());
    }

    @Test
    void 장바구니에서_주문서를_만들면_fromCart가_true다() throws Exception {
        when(memberService.findMember(1L)).thenReturn(
                Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
        com.jhg.hgpage.domain.Product product = new com.jhg.hgpage.domain.Product();
        product.setId(1L);
        product.setName("상품1");
        product.setPrice(10000);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        mockMvc.perform(post("/orders/checkout-form")
                        .with(user(principal()))
                        .with(csrf())
                        .param("items[0].productId", "1")
                        .param("items[0].qty", "2")
                        .param("items[0].selected", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"))
                .andExpect(model().attribute("checkout", hasProperty("fromCart", is(true))));
    }

    @Test
    void 바로구매_주문서는_fromCart가_false다() throws Exception {
        when(memberService.findMember(1L)).thenReturn(
                Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
        com.jhg.hgpage.domain.Product product = new com.jhg.hgpage.domain.Product();
        product.setId(1L);
        product.setName("상품1");
        product.setPrice(10000);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        mockMvc.perform(post("/orders/checkout-form")
                        .with(user(principal()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("qty", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"))
                .andExpect(model().attribute("checkout", hasProperty("fromCart", is(false))));
    }

    /** memberId 1L 소유의 주문 상세 DTO (상품 2개 × 10000원) */
    private com.jhg.hgpage.domain.dto.view.OrderDetailDto detailDto(boolean canceled) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        com.jhg.hgpage.domain.Product product = new com.jhg.hgpage.domain.Product();
        product.setName("테스트상품");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(10);
        product.setInventory(inventory);
        com.jhg.hgpage.domain.Delivery delivery = new com.jhg.hgpage.domain.Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        com.jhg.hgpage.domain.Order order = com.jhg.hgpage.domain.Order.createOrder(member, delivery,
                com.jhg.hgpage.domain.OrderItem.createOrderItem(product, product.getPrice(), 2));
        order.allocate(); // 재고 10 → 예약 2
        if (canceled) {
            order.cancel();
        }
        return com.jhg.hgpage.domain.dto.view.OrderDetailDto.from(order);
    }

    @Test
    void 주문_상세를_렌더링하고_취소가능하면_취소버튼이_보인다() throws Exception {
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(detailDto(false));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(view().name("orderview"))
                .andExpect(model().attributeExists("order"))
                .andExpect(content().string(containsString("테스트상품")))
                .andExpect(content().string(containsString("주문 취소")));
    }

    @Test
    void 백오더_주문_상세에는_입고대기_안내와_취소버튼이_보인다() throws Exception {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        com.jhg.hgpage.domain.Product scarce = new com.jhg.hgpage.domain.Product();
        scarce.setName("부족상품");
        scarce.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(0);
        scarce.setInventory(inventory);
        com.jhg.hgpage.domain.Delivery delivery = new com.jhg.hgpage.domain.Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        com.jhg.hgpage.domain.Order order = com.jhg.hgpage.domain.Order.createOrder(member, delivery,
                com.jhg.hgpage.domain.OrderItem.createOrderItem(scarce, 10000, 2));
        order.allocate(); // BACKORDERED
        when(orderService.findOrderDetail(10L, 1L))
                .thenReturn(com.jhg.hgpage.domain.dto.view.OrderDetailDto.from(order));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("입고 대기")))
                .andExpect(content().string(containsString("주문 취소")));
    }

    @Test
    void 취소된_주문_상세에는_취소버튼이_없다() throws Exception {
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(detailDto(true));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("주문 취소"))));
    }

    @Test
    void 타인_또는_없는_주문_상세는_404_에러페이지를_보여준다() throws Exception {
        when(orderService.findOrderDetail(10L, 1L))
                .thenThrow(new EntityNotFoundException("Order", 10L));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void 주문을_취소하면_상세로_리다이렉트하고_성공_flash를_담는다() throws Exception {
        mockMvc.perform(post("/orders/10/cancel")
                        .with(user(principal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(orderService).cancelOrder(10L, 1L);
    }

    @Test
    void 취소불가_주문이면_에러_flash와_함께_상세로_돌아간다() throws Exception {
        doThrow(new IllegalStateException("이미 배송완료된 상품은 취소가 불가능합니다."))
                .when(orderService).cancelOrder(10L, 1L);

        mockMvc.perform(post("/orders/10/cancel")
                        .with(user(principal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 없는_상품으로_주문서를_요청하면_404_에러페이지를_보여준다() throws Exception {
        when(memberService.findMember(1L)).thenReturn(
                Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/orders/checkout-form")
                        .with(user(principal()))
                        .with(csrf())
                        .param("productId", "99")
                        .param("qty", "1"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void 화면요청에서_IllegalArgumentException이면_400_에러페이지를_보여준다() throws Exception {
        when(memberService.findMember(1L)).thenThrow(new IllegalArgumentException("invalid request"));

        mockMvc.perform(post("/orders/checkout-form")
                        .with(user(principal()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("qty", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"));
    }

    @Test
    void 검증_실패로_주문서를_다시_그릴때_상품을_findAllById로_일괄_조회한다() throws Exception {
        com.jhg.hgpage.domain.Product p1 = new com.jhg.hgpage.domain.Product();
        p1.setId(1L);
        p1.setName("상품1");
        p1.setPrice(10000);
        com.jhg.hgpage.domain.Product p2 = new com.jhg.hgpage.domain.Product();
        p2.setId(2L);
        p2.setName("상품2");
        p2.setPrice(20000);
        when(productRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        // 전 상품 미선택 → 검증 실패 → restoreCheckOutDisplay로 주문서를 다시 렌더링한다
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "08001")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "1")
                        .param("product[0].selected", "false")
                        .param("product[1].id", "2")
                        .param("product[1].quantity", "1")
                        .param("product[1].selected", "false"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"));

        verify(productRepository).findAllById(any());
        verify(productRepository, never()).findById(any()); // 라인별 단건 조회(N+1) 미사용
    }
}
