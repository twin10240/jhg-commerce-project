package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.OrderRepository;
import com.jhg.hgpage.repository.OrderRepositoryQuery;
import com.jhg.hgpage.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceExceptionTest {

    @Mock MemberService memberService;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @InjectMocks OrderService orderService;

    @Test
    void 없는_상품을_주문하면_EntityNotFoundException을_던진다() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.order(
                1L,
                new Address("서울", "관악구", "500"),
                List.of(new OrderService.OrderLine(99L, 1))))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }
}
