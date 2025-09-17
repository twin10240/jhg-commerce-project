package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OrderRepositoryTest {

    @Autowired OrderRepository orderRepository;

    @Test
    public void findOrdersByMemberIdTest() throws Exception {
        List<Order> orders = orderRepository.findOrdersByMemberId(2L);

        for (Order order : orders) {
            System.err.println(order);
        }
    }

}