package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.*;
import com.jhg.hgpage.domain.dto.view.AdminOrderDto;
import com.jhg.hgpage.domain.dto.view.OrderDetailDto;
import com.jhg.hgpage.domain.dto.view.OrderDto;
import com.jhg.hgpage.domain.enums.OrderStatus;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.OrderRepository;
import com.jhg.hgpage.repository.OrderRepositoryQuery;
import com.jhg.hgpage.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final MemberService memberService;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderRepositoryQuery orderRepositoryQuery;
    private final CartService cartService;
    private final BackorderAllocator backorderAllocator;

    public List<Order> findAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    public Long order(Long memberId, Address address, List<OrderLine> lines) {
        Member member = memberService.findMember(memberId);

        Delivery delivery = new Delivery();
        delivery.setAddress(address);

        OrderItem[] orderItems = lines.stream()
                .map(line -> {
                    Product product = findProduct(line.productId());
                    return OrderItem.createOrderItem(product, product.getPrice(), line.quantity());
                })
                .toArray(OrderItem[]::new);

        Order order = Order.createOrder(member, delivery, orderItems);
        // 가용분이 있으면 예약(ORDER), 부족하면 거부하지 않고 백오더(BACKORDERED)로 접수
        order.allocate();
        orderRepository.save(order);

        return order.getId();
    }

    // 장바구니발 주문: 주문 생성과 장바구니 정리를 한 트랜잭션으로 묶는다.
    // 주문이 실패(재고 부족 등)하면 장바구니는 건드리지 않는다.
    @Transactional
    public Long orderFromCart(Long memberId, Address address, List<OrderLine> lines) {
        Long orderId = order(memberId, address, lines);

        List<Long> orderedProductIds = lines.stream()
                .map(OrderLine::productId)
                .toList();
        cartService.removeCartItems(memberId, orderedProductIds);

        return orderId;
    }

    public OrderDetailDto findOrderDetail(Long orderId, Long memberId) {
        return OrderDetailDto.from(findOwnedOrder(orderId, memberId));
    }

    // 관리자 배송 관리 목록
    public List<AdminOrderDto> findAllForAdmin() {
        return orderRepositoryQuery.findAllForAdmin().stream()
                .map(AdminOrderDto::from)
                .toList();
    }

    @Transactional
    public void completeDelivery(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        order.completeDelivery();
    }

    @Transactional
    public void cancelOrder(Long orderId, Long memberId) {
        Order order = findOwnedOrder(orderId, memberId);
        // 예약은 ORDER 상태에만 존재한다. 취소로 예약이 풀리면 가용분이 늘어나므로,
        // 그 상품을 기다리던 백오더를 재할당(승격)하도록 트리거한다.
        // BACKORDERED 취소는 풀릴 예약이 없어 트리거할 필요가 없다.
        boolean wasReserved = order.getStatus() == OrderStatus.ORDER;
        order.cancel();
        if (wasReserved) {
            List<Long> productIds = order.getOrderItems().stream()
                    .map(orderItem -> orderItem.getProduct().getId())
                    .distinct()
                    .toList();
            backorderAllocator.allocate(productIds);
        }
    }

    // 본인 주문만 반환. 타인 주문은 존재 자체를 숨기기 위해 404(EntityNotFoundException)로 처리(IDOR 방지)
    private Order findOwnedOrder(Long orderId, Long memberId) {
        Order order = orderRepositoryQuery.findDetailById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        if (!order.getMember().getId().equals(memberId)) {
            throw new EntityNotFoundException("Order", orderId);
        }
        return order;
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product", productId));
    }

    public record OrderLine(Long productId, int quantity) {}

    public List<OrderDto> findOrders(Long memberId) {
        List<Order> orders = orderRepositoryQuery.findOrders(memberId);
        return orders.stream()
                .map(o -> new OrderDto(o.getId(), o.getStatus(), o.getTotalPrice(), o.getOrderDate()))
                .collect(Collectors.toList());
    }
}
