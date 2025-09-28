package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.*;
import com.jhg.hgpage.domain.dto.view.OrderDto;
import com.jhg.hgpage.domain.enums.OrderStatus;
import com.jhg.hgpage.repositoey.OrderRepository;
import com.jhg.hgpage.repositoey.OrderRepositoryQuery;
import com.jhg.hgpage.repositoey.ProductRepository;
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

    public List<Order> findAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    public Long order(Long memberId, Long product_id, int quantity) {
        Member member = memberService.findById(memberId);

        Delivery delivery = new Delivery();
        delivery.setAddress(member.getAddress());

        Product product = productRepository.findById(product_id).get();
        OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), quantity);

        Order order = orderRepository.save(Order.createOrder(member, delivery, orderItem));

        return order.getId();
    }

    public List<OrderDto> findOrders(Long memberId) {
        List<Order> orders = orderRepositoryQuery.findOrders(memberId);
        return orders.stream().map(o -> new OrderDto(1L, OrderStatus.ORDER, o.getTotalPrice(), o.getOrderDate())).collect(Collectors.toList());
    }

//    public List<Order> findOrders(SearchOption searchOption, Long memberId) {
//        return orderRepositoryQuery.findOrders(searchOption, memberId);
//    }
}
