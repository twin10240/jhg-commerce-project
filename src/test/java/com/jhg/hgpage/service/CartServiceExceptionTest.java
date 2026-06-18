package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.CartService;

import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.repository.CartRepository;
import com.jhg.hgpage.oms.repository.CartRepositoryQuery;
import com.jhg.hgpage.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceExceptionTest {

    @Mock ProductRepository productRepository;
    @Mock CartRepository cartRepository;
    @Mock CartRepositoryQuery cartRepositoryQuery;
    @InjectMocks CartService cartService;

    @Test
    void 없는_상품을_장바구니에_담으면_EntityNotFoundException을_던진다() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addCartItem(1L, 99L, 1))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }
}
