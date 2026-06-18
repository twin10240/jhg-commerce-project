package com.jhg.hgpage.service;

import com.jhg.hgpage.contract.InventoryQueryPort;

import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.view.ProductCardDto;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 메인 상품 그리드 조회 — 카탈로그 페이지(Product)에 가용수량을 InventoryQueryPort(포트)로 합쳐
 * ProductCardDto로 반환한다. 가용수량은 객체 그래프(Product.inventory)가 아니라 포트로만 얻는다.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceFindPageTest {

    @Mock ProductRepository productRepository;
    @Mock InventoryQueryPort inventoryQueryPort;
    @InjectMocks ProductService productService;

    private Product product(long id, String name, int price) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(price);
        return p;
    }

    @Test
    void keyword가_있으면_이름검색_카탈로그페이지에_가용수량을_합쳐_카드로_반환한다() {
        Pageable pageable = PageRequest.of(0, 12);
        when(productRepository.findByNameContainingIgnoreCase("상품", pageable))
                .thenReturn(new PageImpl<>(List.of(product(1L, "상품1", 10000))));
        when(inventoryQueryPort.availableByProductIds(List.of(1L))).thenReturn(Map.of(1L, 7));

        Page<ProductCardDto> result = productService.findCardPage("상품", pageable);

        assertThat(result.getContent()).singleElement().satisfies(card -> {
            assertThat(card.getId()).isEqualTo(1L);
            assertThat(card.getName()).isEqualTo("상품1");
            assertThat(card.getPrice()).isEqualTo(10000);
            assertThat(card.getAvailableQty()).isEqualTo(7);
        });
        // 이름검색 카탈로그 쿼리만 사용 — 재고 fetch join 쿼리를 타지 않는다
        verify(productRepository).findByNameContainingIgnoreCase("상품", pageable);
        verifyNoMoreInteractions(productRepository);
    }

    @Test
    void keyword가_공백이면_전체_카탈로그페이지에_가용수량을_합친다() {
        Pageable pageable = PageRequest.of(0, 12);
        when(productRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(product(2L, "상품2", 20000))));
        when(inventoryQueryPort.availableByProductIds(List.of(2L))).thenReturn(Map.of(2L, 0));

        Page<ProductCardDto> result = productService.findCardPage("   ", pageable);

        assertThat(result.getContent()).singleElement().satisfies(card -> {
            assertThat(card.getId()).isEqualTo(2L);
            assertThat(card.getAvailableQty()).isEqualTo(0);
        });
        verify(productRepository).findAll(pageable);
        verifyNoMoreInteractions(productRepository);
    }

    @Test
    void 포트에_없는_상품의_가용수량은_0으로_본다() {
        Pageable pageable = PageRequest.of(0, 12);
        when(productRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(product(3L, "상품3", 30000))));
        when(inventoryQueryPort.availableByProductIds(List.of(3L))).thenReturn(Map.of()); // 비어 있음

        Page<ProductCardDto> result = productService.findCardPage("", pageable);

        assertThat(result.getContent()).singleElement()
                .satisfies(card -> assertThat(card.getAvailableQty()).isEqualTo(0));
    }
}
