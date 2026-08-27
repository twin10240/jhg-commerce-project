package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.dto.AdminOrderDto;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.service.PaymentAdminService;
import com.jhg.hgpage.oms.web.controller.OrderAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
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

@WebMvcTest(OrderAdminController.class)
@Import(SecurityConfig.class)
class OrderAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean OrderService orderService;
    @MockBean PaymentAdminService paymentAdminService;

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@admin.com", "관리자", "010-1111-2222", "password", Role.ADMIN);
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    private Product sampleProduct() {
        Product product = new Product();
        product.setId(1L);
        product.setName("상품1");
        product.setPrice(10000);
        return product;
    }

    private AdminOrderDto adminOrderDto() {
        com.jhg.hgpage.oms.domain.Member member = com.jhg.hgpage.oms.domain.Member.createUser(
                "주문자A", "010-0000-0000", new com.jhg.hgpage.oms.domain.Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Delivery delivery = new com.jhg.hgpage.oms.domain.Delivery();
        delivery.setAddress(new com.jhg.hgpage.oms.domain.Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Order order = com.jhg.hgpage.oms.domain.Order.createOrder(member, delivery,
                com.jhg.hgpage.oms.domain.OrderItem.createOrderItem(sampleProduct(), 10000, 2));
        return AdminOrderDto.from(order);
    }

    private AdminOrderDto backorderedAdminOrderDto() {
        com.jhg.hgpage.oms.domain.Member member = com.jhg.hgpage.oms.domain.Member.createUser(
                "주문자A", "010-0000-0000", new com.jhg.hgpage.oms.domain.Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Delivery delivery = new com.jhg.hgpage.oms.domain.Delivery();
        delivery.setAddress(new com.jhg.hgpage.oms.domain.Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Order order = com.jhg.hgpage.oms.domain.Order.createOrder(member, delivery,
                com.jhg.hgpage.oms.domain.OrderItem.createOrderItem(sampleProduct(), 10000, 2));
        order.markBackordered();
        return AdminOrderDto.from(order);
    }

    private AdminOrderDto shippedAdminOrderDto() {
        com.jhg.hgpage.oms.domain.Member member = com.jhg.hgpage.oms.domain.Member.createUser(
                "주문자A", "010-0000-0000", new com.jhg.hgpage.oms.domain.Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Delivery delivery = new com.jhg.hgpage.oms.domain.Delivery();
        delivery.setAddress(new com.jhg.hgpage.oms.domain.Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Order order = com.jhg.hgpage.oms.domain.Order.createOrder(member, delivery,
                com.jhg.hgpage.oms.domain.OrderItem.createOrderItem(sampleProduct(), 10000, 2));
        order.ship();
        delivery.recordShipment("MOCK", "테스트택배", "MOCK-10", Instant.parse("2026-08-27T06:30:00.123456Z"));
        return AdminOrderDto.from(order);
    }

    @Test
    void 관리자는_주문목록을_조회한다() throws Exception {
        when(orderService.findAllForAdmin()).thenReturn(List.of(adminOrderDto()));

        mockMvc.perform(get("/admin/orders").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/orders"))
                .andExpect(model().attributeExists("orders"))
                .andExpect(content().string(containsString("주문자A")))
                .andExpect(content().string(containsString(">재고 확보</span>")))
                .andExpect(content().string(containsString("출고 처리")))
                .andExpect(content().string(containsString("선택 출고 처리")))
                .andExpect(content().string(containsString("/admin/orders/ships")))
                .andExpect(content().string(containsString("name=\"orderIds\"")));
    }

    @Test
    void 출고된_주문에는_배송_완료_버튼만_표시한다() throws Exception {
        when(orderService.findAllForAdmin()).thenReturn(List.of(shippedAdminOrderDto()));

        mockMvc.perform(get("/admin/orders").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/orders/deliver")))
                .andExpect(content().string(containsString("테스트택배 MOCK-10")))
                .andExpect(content().string(containsString(">배송 완료</button>")))
                .andExpect(content().string(not(containsString("/admin/orders/ship\""))));
    }

    @Test
    void 백오더_주문의_상품과_수량을_분리하고_입고필요_상태를_표시한다() throws Exception {
        when(orderService.findAllForAdmin()).thenReturn(List.of(backorderedAdminOrderDto()));

        mockMvc.perform(get("/admin/orders").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("style=\"width:140px\">주문자</th>")))
                .andExpect(content().string(containsString("style=\"width:150px\">주문 상품</th>")))
                .andExpect(content().string(containsString(">수량</th>")))
                .andExpect(content().string(containsString("상품1")))
                .andExpect(content().string(containsString(">2</div>")))
                .andExpect(content().string(not(containsString("상품1 × 2"))))
                .andExpect(content().string(containsString(">입고 대기</span>")))
                .andExpect(content().string(containsString("입고 필요")));
    }

    @Test
    void 복합_주문상태는_구분점에서_줄바꿈한다() throws Exception {
        AdminOrderDto order = backorderedAdminOrderDto();
        ReflectionTestUtils.setField(order, "orderStatusLabel", "결제 완료 · 입고 대기");
        when(orderService.findAllForAdmin()).thenReturn(List.of(order));

        mockMvc.perform(get("/admin/orders").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("결제 완료<br>입고 대기")));
    }

    @Test
    void 일반사용자는_주문목록에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/orders").with(user(normalUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 출고_처리하면_주문목록으로_리다이렉트하고_성공메시지를_담는다() throws Exception {
        mockMvc.perform(post("/admin/orders/ship")
                        .with(user(admin()))
                        .with(csrf())
                        .param("orderId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute("successMessage", "출고 처리되었습니다. (주문 #10)"));

        verify(orderService).shipOrder(10L);
    }

    @Test
    void 출고_불가_주문이면_에러메시지와_함께_목록으로_돌아간다() throws Exception {
        doThrow(new IllegalStateException("이미 출고 완료된 주문입니다."))
                .when(orderService).shipOrder(10L);

        mockMvc.perform(post("/admin/orders/ship")
                        .with(user(admin()))
                        .with(csrf())
                        .param("orderId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 단건_출고_WMS통신실패는_주문번호와_조치를_안내한다() throws Exception {
        doThrow(new ResourceAccessException("Connection refused"))
                .when(orderService).shipOrder(10L);

        mockMvc.perform(post("/admin/orders/ship")
                        .with(user(admin()))
                        .with(csrf())
                        .param("orderId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute("errorMessage",
                        "주문 #10 — [WMS_UNAVAILABLE] WMS와 통신할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    void 선택한_주문을_모두_출고_처리한다() throws Exception {
        mockMvc.perform(post("/admin/orders/ships")
                        .with(user(admin()))
                        .with(csrf())
                        .param("orderIds", "10", "11"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute(
                        "successMessage", "출고 처리 결과: 성공 2건 / 실패 0건."));

        verify(orderService).shipOrder(10L);
        verify(orderService).shipOrder(11L);
    }

    @Test
    void 선택_출고는_실패한_주문을_제외하고_계속_처리한다() throws Exception {
        doThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", new HttpHeaders(),
                        "예약이 없어 출고할 수 없습니다. orderId=11".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8))
                .when(orderService).shipOrder(11L);

        mockMvc.perform(post("/admin/orders/ships")
                        .with(user(admin()))
                        .with(csrf())
                        .param("orderIds", "10", "11"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute(
                        "errorMessage", "출고 처리 결과: 성공 1건 / 실패 1건. 실패 사유: "
                                + "주문 #11 — [WMS_RESERVATION_NOT_FOUND] WMS 예약 내역이 없습니다. "
                                + "OMS/WMS 동기화 상태를 확인해 주세요."));

        verify(orderService).shipOrder(10L);
        verify(orderService).shipOrder(11L);
    }

    @Test
    void 선택_출고는_중복_주문번호를_한번만_처리한다() throws Exception {
        mockMvc.perform(post("/admin/orders/ships")
                        .with(user(admin()))
                        .with(csrf())
                        .param("orderIds", "10", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(
                        "successMessage", "출고 처리 결과: 성공 1건 / 실패 0건."));

        verify(orderService).shipOrder(10L);
    }

    @Test
    void 선택한_주문이_없으면_출고하지_않는다() throws Exception {
        mockMvc.perform(post("/admin/orders/ships")
                        .with(user(admin()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute(
                        "errorMessage", "출고할 주문을 선택해주세요."));

        verifyNoInteractions(orderService);
    }

    @Test
    void 배송_완료하면_주문목록으로_리다이렉트하고_성공메시지를_담는다() throws Exception {
        mockMvc.perform(post("/admin/orders/deliver")
                        .with(user(admin()))
                        .with(csrf())
                        .param("orderId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attribute("successMessage", "배송 완료되었습니다. (주문 #10)"));

        verify(orderService).deliverOrder(10L);
    }

    @Test
    void 관리자는_할당검토를_같은주문으로_다시처리한다() throws Exception {
        mockMvc.perform(post("/admin/orders/10/allocation/retry")
                        .with(user(admin()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"));

        verify(paymentAdminService).retryAllocation(10L);
    }

    @Test
    void 일반사용자는_할당재시도를_할수없다() throws Exception {
        mockMvc.perform(post("/admin/orders/10/allocation/retry")
                        .with(user(normalUser()))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentAdminService);
    }

    @Test
    void 할당재시도는_CSRF가_필요하다() throws Exception {
        mockMvc.perform(post("/admin/orders/10/allocation/retry")
                        .with(user(admin())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentAdminService);
    }
}
