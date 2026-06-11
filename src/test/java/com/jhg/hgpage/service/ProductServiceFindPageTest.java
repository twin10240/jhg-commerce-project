package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceFindPageTest {

    @Mock ProductRepository productRepository;
    @InjectMocks ProductService productService;

    @Test
    void keyword가_있으면_재고포함_이름검색_페이징으로_위임한다() {
        Pageable pageable = PageRequest.of(0, 12);
        Page<Product> expected = new PageImpl<>(List.of());
        when(productRepository.findPageByNameWithInventory("상품", pageable)).thenReturn(expected);

        Page<Product> result = productService.findPage("상품", pageable);

        assertThat(result).isSameAs(expected);
        verify(productRepository).findPageByNameWithInventory("상품", pageable);
        verifyNoMoreInteractions(productRepository);
    }

    @Test
    void keyword가_공백이면_재고포함_전체조회_페이징으로_위임한다() {
        Pageable pageable = PageRequest.of(0, 12);
        Page<Product> expected = new PageImpl<>(List.of());
        when(productRepository.findPageWithInventory(pageable)).thenReturn(expected);

        Page<Product> result = productService.findPage("   ", pageable);

        assertThat(result).isSameAs(expected);
        verify(productRepository).findPageWithInventory(pageable);
        verifyNoMoreInteractions(productRepository);
    }
}
