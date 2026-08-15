package com.jhg.hgpage.controller.order;
import com.jhg.hgpage.oms.web.controller.OrderController;

import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.exception.NotEnoughStockException;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.service.PaymentFacade;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.contract.InventoryQueryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
import static org.mockito.Mockito.verifyNoInteractions;
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
    @MockBean PaymentFacade paymentFacade;
    @MockBean CustomerReturnService customerReturnService;
    @MockBean InventoryQueryPort inventoryQueryPort;

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

        verifyNoInteractions(paymentFacade);
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

        verifyNoInteractions(paymentFacade);
    }

    @Test
    void 정상주문이면_생성된_주문상세로_리다이렉트한다() throws Exception {
        when(paymentFacade.checkout(eq(1L), any(Address.class), anyList(), eq(false))).thenReturn(10L);

        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                .param("product[0].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10?created=true"));

        verify(paymentFacade).checkout(eq(1L), any(Address.class), anyList(), eq(false));
    }

    @Test
    void 선택된_상품만_주문된다() throws Exception {
        when(paymentFacade.checkout(eq(1L), any(Address.class), anyList(), eq(false))).thenReturn(10L);

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
                .andExpect(redirectedUrl("/orders/10?created=true"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderService.OrderLine>> linesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(paymentFacade).checkout(eq(1L), any(Address.class), linesCaptor.capture(), eq(false));

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

        verifyNoInteractions(paymentFacade);
    }

    @Test
    void 재고가_부족하면_main으로_리다이렉트하고_에러메시지를_flash에_담는다() throws Exception {
        doThrow(new NotEnoughStockException("need more stock"))
                .when(paymentFacade).checkout(anyLong(), any(Address.class), anyList(), eq(false));

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
        doThrow(new ObjectOptimisticLockingFailureException(Object.class, 1L))
                .when(paymentFacade).checkout(anyLong(), any(Address.class), anyList(), eq(false));

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
    void 장바구니발_주문이면_선택상품과_fromCart를_전달한다() throws Exception {
        when(paymentFacade.checkout(eq(1L), any(Address.class), anyList(), eq(true))).thenReturn(10L);

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
                .andExpect(redirectedUrl("/orders/10?created=true"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderService.OrderLine>> linesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(paymentFacade).checkout(eq(1L), any(Address.class), linesCaptor.capture(), eq(true));

        List<OrderService.OrderLine> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).productId()).isEqualTo(1L);
    }

    @Test
    void 바로구매_주문이면_장바구니_정리없이_주문한다() throws Exception {
        when(paymentFacade.checkout(eq(1L), any(Address.class), anyList(), eq(false))).thenReturn(10L);

        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                .param("product[0].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10?created=true"));

        verify(paymentFacade).checkout(eq(1L), any(Address.class), anyList(), eq(false));
    }

    @Test
    void 장바구니에서_주문서를_만들면_fromCart가_true다() throws Exception {
        when(memberService.findMember(1L)).thenReturn(
                Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
        com.jhg.hgpage.catalog.Product product = new com.jhg.hgpage.catalog.Product();
        product.setId(1L);
        product.setName("상품1");
        product.setPrice(10000);
        when(productRepository.findAllById(any())).thenReturn(List.of(product));

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
        com.jhg.hgpage.catalog.Product product = new com.jhg.hgpage.catalog.Product();
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
                .andExpect(model().attribute("checkout", hasProperty("fromCart", is(false))))
                .andExpect(content().string(containsString("모의 카드 결제")))
                .andExpect(content().string(containsString("결제하고 주문하기")));
    }

    /** memberId 1L 소유의 주문 상세 DTO (상품 2개 × 10000원) */
    private com.jhg.hgpage.oms.dto.OrderDetailDto detailDto(boolean canceled, boolean shipped) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        com.jhg.hgpage.catalog.Product product = new com.jhg.hgpage.catalog.Product();
        product.setName("테스트상품");
        product.setPrice(10000);
        com.jhg.hgpage.oms.domain.Delivery delivery = new com.jhg.hgpage.oms.domain.Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Order order = com.jhg.hgpage.oms.domain.Order.createOrder(member, delivery,
                com.jhg.hgpage.oms.domain.OrderItem.createOrderItem(product, product.getPrice(), 2));
        order.markOrdered(); // ORDER 상태(예약 성공)
        if (shipped) {
            order.ship();
        } else if (canceled) {
            order.cancel();
        }
        return com.jhg.hgpage.oms.dto.OrderDetailDto.from(order);
    }

    @Test
    void 주문_상세를_렌더링하고_취소가능하면_취소버튼이_보인다() throws Exception {
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(detailDto(false, false));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(view().name("orderview"))
                .andExpect(model().attributeExists("order"))
                .andExpect(content().string(containsString("테스트상품")))
                .andExpect(content().string(containsString("주문 취소")));
    }

    @Test
    void 배송완료_주문은_남은수량만큼_반품폼과_CSRF를_렌더링한다() throws Exception {
        DeliveredFixture fixture = deliveredFixture();
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(fixture.detail());
        when(customerReturnService.findForOwnedOrder(10L, 1L)).thenReturn(List.of());

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("반품 요청")))
                .andExpect(content().string(containsString("type=\"number\"")))
                .andExpect(content().string(containsString("max=\"2\"")))
                .andExpect(content().string(containsString("maxlength=\"500\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void 진행중_반품수량은_새_반품의_최대수량에서_제외된다() throws Exception {
        DeliveredFixture fixture = deliveredFixture();
        CustomerReturn previous = CustomerReturn.create(fixture.order(), UUID.randomUUID(), "한 개 반품",
                List.of(new CustomerReturn.RequestItem(fixture.orderItem(), 1)));
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(fixture.detail());
        when(customerReturnService.findForOwnedOrder(10L, 1L)).thenReturn(List.of(previous));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("max=\"1\"")))
                .andExpect(content().string(containsString("반품 내역")));
    }

    @Test
    void 반품완료_주문은_배송상태와_반품상태를_분리해_표시한다() throws Exception {
        DeliveredFixture fixture = deliveredFixture();
        CustomerReturn completed = CustomerReturn.create(fixture.order(), UUID.randomUUID(), "단순 변심",
                List.of(new CustomerReturn.RequestItem(fixture.orderItem(), 1)));
        completed.complete(List.of(new CustomerReturn.ResultItem(
                fixture.orderItem().getId(), 1,
                com.jhg.hgpage.oms.domain.enums.ReturnDisposition.RESTOCKED)));
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(fixture.detail());
        when(customerReturnService.findForOwnedOrder(10L, 1L)).thenReturn(List.of(completed));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<dt>배송상태</dt><dd>배송 완료</dd>")))
                .andExpect(content().string(containsString("<dt>반품상태</dt>")))
                .andExpect(content().string(containsString("반품 완료")));
    }

    @Test
    void 출고전과_배송중_주문에는_반품폼이_없다() throws Exception {
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(detailDto(false, false));
        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/orders/10/returns"))));

        when(orderService.findOrderDetail(10L, 1L)).thenReturn(detailDto(false, true));
        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/orders/10/returns"))));
    }

    @Test
    void POST에서_보존한_검증오류를_GET이_인라인으로_렌더링한다() throws Exception {
        DeliveredFixture fixture = deliveredFixture();
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(fixture.detail());
        when(customerReturnService.findForOwnedOrder(10L, 1L)).thenReturn(List.of());
        com.jhg.hgpage.oms.web.form.CustomerReturnForm form = new com.jhg.hgpage.oms.web.form.CustomerReturnForm();
        form.setReason("");
        form.getLines().add(new com.jhg.hgpage.oms.web.form.CustomerReturnForm.Line(101L, 1));
        org.springframework.validation.BeanPropertyBindingResult errors =
                new org.springframework.validation.BeanPropertyBindingResult(form, "returnForm");
        errors.rejectValue("reason", "NotBlank", "반품 사유를 입력해주세요.");

        mockMvc.perform(get("/orders/10")
                        .with(user(principal()))
                        .flashAttr("returnForm", form)
                        .flashAttr("org.springframework.validation.BindingResult.returnForm", errors))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("반품 사유를 입력해주세요.")));
    }

    @Test
    void 남은수량이_동시에_소진돼도_POST에서_보존한_전역오류를_렌더링한다() throws Exception {
        DeliveredFixture fixture = deliveredFixture();
        CustomerReturn previous = CustomerReturn.create(fixture.order(), UUID.randomUUID(), "전량 반품",
                List.of(new CustomerReturn.RequestItem(fixture.orderItem(), 2)));
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(fixture.detail());
        when(customerReturnService.findForOwnedOrder(10L, 1L)).thenReturn(List.of(previous));
        com.jhg.hgpage.oms.web.form.CustomerReturnForm form = new com.jhg.hgpage.oms.web.form.CustomerReturnForm();
        form.setReason("불량");
        form.getLines().add(new com.jhg.hgpage.oms.web.form.CustomerReturnForm.Line(101L, 1));
        org.springframework.validation.BeanPropertyBindingResult errors =
                new org.springframework.validation.BeanPropertyBindingResult(form, "returnForm");
        errors.reject("invalidReturn", "반품 가능 수량을 초과했습니다.");

        mockMvc.perform(get("/orders/10")
                        .with(user(principal()))
                        .flashAttr("returnForm", form)
                        .flashAttr("org.springframework.validation.BindingResult.returnForm", errors))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("반품 가능 수량을 초과했습니다.")));
    }

    @Test
    void 출고된_주문_상세에는_출고완료로_표시한다() throws Exception {
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(detailDto(false, true));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("출고 완료")))
                .andExpect(content().string(containsString("<dt>배송상태</dt><dd>출고 완료</dd>")))
                .andExpect(content().string(containsString(">출고 완료</span>")));
    }

    @Test
    void 백오더_주문_상세에는_입고대기_안내와_취소버튼이_보인다() throws Exception {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        com.jhg.hgpage.catalog.Product scarce = new com.jhg.hgpage.catalog.Product();
        scarce.setName("부족상품");
        scarce.setPrice(10000);
        com.jhg.hgpage.oms.domain.Delivery delivery = new com.jhg.hgpage.oms.domain.Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Order order = com.jhg.hgpage.oms.domain.Order.createOrder(member, delivery,
                com.jhg.hgpage.oms.domain.OrderItem.createOrderItem(scarce, 10000, 2));
        order.markBackordered(); // 백오더 접수 상태
        when(orderService.findOrderDetail(10L, 1L))
                .thenReturn(com.jhg.hgpage.oms.dto.OrderDetailDto.from(order));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("입고 대기")))
                .andExpect(content().string(containsString("주문 취소")));
    }

    @Test
    void 취소된_주문_상세에는_취소버튼이_없다() throws Exception {
        when(orderService.findOrderDetail(10L, 1L)).thenReturn(detailDto(true, false));

        mockMvc.perform(get("/orders/10").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/orders/10/cancel"))));
    }

    @Test
    void 내_주문_화면을_렌더링한다() throws Exception {
        when(orderService.findOrders(1L)).thenReturn(List.of(
                new com.jhg.hgpage.oms.dto.OrderDto(
                        10L,
                        com.jhg.hgpage.oms.domain.enums.OrderStatus.BACKORDERED,
                        com.jhg.hgpage.oms.domain.enums.DeliveryStatus.READY,
                        20000,
                        java.time.LocalDateTime.of(2026, 7, 25, 12, 0)),
                new com.jhg.hgpage.oms.dto.OrderDto(
                        11L,
                        com.jhg.hgpage.oms.domain.enums.OrderStatus.ORDER,
                        com.jhg.hgpage.oms.domain.enums.DeliveryStatus.SHIPPED,
                        10000,
                        java.time.LocalDateTime.of(2026, 7, 24, 12, 0))));

        mockMvc.perform(get("/orders").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(view().name("orders"))
                .andExpect(model().attributeExists("orders"))
                .andExpect(content().string(containsString("입고 대기")))
                .andExpect(content().string(containsString("출고 완료")))
                .andExpect(content().string(containsString("/orders/10")));
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
        when(paymentFacade.cancelOrder(10L, 1L)).thenReturn(true);

        mockMvc.perform(post("/orders/10/cancel")
                        .with(user(principal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10"))
                .andExpect(flash().attribute("successMessage", "주문 취소가 접수되었습니다. 환불 상태를 확인해주세요."));

        verify(paymentFacade).cancelOrder(10L, 1L);
    }

    @Test
    void 결제재시도는_소유자정보를_위임하고_한국어_flash를_담는다() throws Exception {
        mockMvc.perform(post("/orders/10/payment/retry")
                        .with(user(principal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10"))
                .andExpect(flash().attribute("successMessage", "결제를 다시 시도했습니다."));

        verify(paymentFacade).retryPayment(10L, 1L);
    }

    @Test
    void 결제재시도는_CSRF가_필요하다() throws Exception {
        mockMvc.perform(post("/orders/10/payment/retry").with(user(principal())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 타인주문_결제재시도는_404를_유지한다() throws Exception {
        doThrow(new EntityNotFoundException("Order", 10L))
                .when(paymentFacade).retryPayment(10L, 1L);

        mockMvc.perform(post("/orders/10/payment/retry")
                        .with(user(principal()))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void 재결제불가_주문은_한국어_에러_flash로_돌아간다() throws Exception {
        doThrow(new IllegalStateException("재결제 가능한 상태가 아닙니다."))
                .when(paymentFacade).retryPayment(10L, 1L);

        mockMvc.perform(post("/orders/10/payment/retry")
                        .with(user(principal()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10"))
                .andExpect(flash().attribute("errorMessage", "재결제 가능한 상태가 아닙니다."));
    }

    @Test
    void 취소불가_주문이면_에러_flash와_함께_상세로_돌아간다() throws Exception {
        doThrow(new IllegalStateException("이미 출고 완료된 상품은 취소가 불가능합니다."))
                .when(paymentFacade).cancelOrder(10L, 1L);

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
        com.jhg.hgpage.catalog.Product p1 = new com.jhg.hgpage.catalog.Product();
        p1.setId(1L);
        p1.setName("상품1");
        p1.setPrice(10000);
        com.jhg.hgpage.catalog.Product p2 = new com.jhg.hgpage.catalog.Product();
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

    private DeliveredFixture deliveredFixture() {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        com.jhg.hgpage.catalog.Product product = new com.jhg.hgpage.catalog.Product();
        product.setId(501L);
        product.setName("배송완료상품");
        product.setPrice(10000);
        com.jhg.hgpage.oms.domain.Delivery delivery = new com.jhg.hgpage.oms.domain.Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.OrderItem item =
                com.jhg.hgpage.oms.domain.OrderItem.createOrderItem(product, 10000, 2);
        ReflectionTestUtils.setField(item, "id", 101L);
        com.jhg.hgpage.oms.domain.Order order = com.jhg.hgpage.oms.domain.Order.createOrder(member, delivery, item);
        ReflectionTestUtils.setField(order, "id", 10L);
        order.ship();
        order.deliver();
        return new DeliveredFixture(order, item, com.jhg.hgpage.oms.dto.OrderDetailDto.from(order));
    }

    private record DeliveredFixture(com.jhg.hgpage.oms.domain.Order order,
                                    com.jhg.hgpage.oms.domain.OrderItem orderItem,
                                    com.jhg.hgpage.oms.dto.OrderDetailDto detail) {}

    @Test
    void 장바구니_주문서_생성시_상품을_findAllById로_일괄_조회한다() throws Exception {
        when(memberService.findMember(1L)).thenReturn(
                Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
        com.jhg.hgpage.catalog.Product p1 = new com.jhg.hgpage.catalog.Product();
        p1.setId(1L);
        p1.setName("상품1");
        p1.setPrice(10000);
        com.jhg.hgpage.catalog.Product p2 = new com.jhg.hgpage.catalog.Product();
        p2.setId(2L);
        p2.setName("상품2");
        p2.setPrice(20000);
        when(productRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        mockMvc.perform(post("/orders/checkout-form")
                        .with(user(principal()))
                        .with(csrf())
                        .param("items[0].productId", "1")
                        .param("items[0].qty", "1")
                        .param("items[0].selected", "true")
                        .param("items[1].productId", "2")
                        .param("items[1].qty", "2")
                        .param("items[1].selected", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"));

        verify(productRepository).findAllById(any());
        verify(productRepository, never()).findById(any()); // 라인별 단건 조회(N+1) 미사용
    }
}
