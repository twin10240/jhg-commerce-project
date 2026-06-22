package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.CartService;
import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.dto.AdminOrderDto;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceAdminTest {

    @Mock MemberService memberService;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @Mock CartService cartService;
    @Mock InventoryPort inventoryPort;
    @InjectMocks OrderService orderService;

    private Order newOrder(String memberName) {
        Member member = Member.createUser(memberName, "010-0000-0000", new Address("서울", "관악구", "500"));
        Product product = new Product();
        product.setId(1L);
        product.setName("테스트상품");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(10);
        product.setInventory(inventory);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(product, product.getPrice(), 2));
        order.markOrdered(); // 예약 성공 상태(ORDER)로 둔다 — 예약 자체는 서비스/포트가 담당
        return order;
    }

    @Test
    void 전체_주문을_관리자용_DTO로_매핑한다() {
        Order active = newOrder("회원A");
        Order canceled = newOrder("회원B");
        canceled.cancel();
        when(orderRepositoryQuery.findAllForAdmin()).thenReturn(List.of(active, canceled));

        List<AdminOrderDto> result = orderService.findAllForAdmin();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMemberName()).isEqualTo("회원A");
        assertThat(result.get(0).getTotalPrice()).isEqualTo(20000);
        assertThat(result.get(0).isCompletable()).isTrue();   // ORDER + READY → 배송완료 버튼 노출
        assertThat(result.get(1).getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(result.get(1).isCompletable()).isFalse();  // 취소된 주문은 처리 불가
    }

    @Test
    void 배송완료_처리하면_배송상태가_COMP가_되고_재고_출고를_포트에_위임한다() {
        Order order = newOrder("회원A"); // 상품1, 수량 2

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        orderService.completeDelivery(10L);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.COMP);
        // 실물 차감은 도메인이 아니라 InventoryPort(WMS)에 위임한다
        verify(inventoryPort).shipAll(Map.of(1L, 2));
    }

    @Test
    void 없는_주문의_배송완료는_EntityNotFoundException을_던진다() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.completeDelivery(99L))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
