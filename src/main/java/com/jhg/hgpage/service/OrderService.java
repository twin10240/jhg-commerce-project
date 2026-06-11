package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.*;
import com.jhg.hgpage.domain.dto.view.OrderDto;
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

    public List<Order> findAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    public Long order(Long memberId, Long productId, int quantity) {
        Member member = memberService.findMember(memberId);

        Delivery delivery = new Delivery();
        delivery.setAddress(member.getAddress());

        Product product = findProduct(productId);
        OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), quantity);

        Order order = orderRepository.save(Order.createOrder(member, delivery, orderItem));

        return order.getId();
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

        Order order = orderRepository.save(Order.createOrder(member, delivery, orderItems));

        return order.getId();
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

//    public List<Order> findOrders(SearchOption searchOption, Long memberId) {
//        return orderRepositoryQuery.findOrders(searchOption, memberId);
//    }
}
