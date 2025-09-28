package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.*;
import com.jhg.hgpage.domain.dto.view.OrderDto;
import com.jhg.hgpage.domain.enums.OrderStatus;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@SpringBootTest
@Transactional
class OrderServiceTest {

    @Autowired OrderService orderService;
    @Autowired MemberService memberService;

    @Test
    public void 내주문조회_테스트() {
        Member member = memberService.findById(2L);


        Delivery delivery = new Delivery();
        delivery.setAddress(member.getAddress());

        Inventory inventory = new Inventory();
        inventory.setOnHandQty(100);
        inventory.setReservedQty(0);

        Product product = new Product();
        product.setName("TEST1");
        product.setPrice(1000);
        product.setInventory(inventory);

        OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), 50);

        Inventory inventory2 = new Inventory();
        inventory2.setOnHandQty(50);
        inventory2.setReservedQty(0);

        Product product2 = new Product();
        product2.setName("TEST2");
        product2.setPrice(1000);
        product2.setInventory(inventory2);

        OrderItem orderItem2 = OrderItem.createOrderItem(product, product.getPrice(), 10);

        Order order = Order.createOrder(member, delivery, orderItem, orderItem2);
        Order order2 = Order.createOrder(member, delivery, orderItem, orderItem, orderItem2);
        List<Order> orders = new ArrayList<>();
        orders.add(order);
        orders.add(order2);

        List<OrderDto> collect = orders.stream().map(o -> new OrderDto(1L, OrderStatus.ORDER, o.getTotalPrice(), o.getOrderDate())).collect(Collectors.toList());
        for (OrderDto o : collect) {
            System.err.println(o.toString());
        }
    }

}