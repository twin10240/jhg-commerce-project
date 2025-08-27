package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.repositoey.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    public List<Order> findOrders() {
        return orderRepository.findAll();
    }

    public Long save(Order order) {
        orderRepository.save(order);

        return order.getId();
    }
}
