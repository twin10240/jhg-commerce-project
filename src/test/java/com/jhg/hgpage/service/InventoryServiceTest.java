package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock ProductRepository productRepository;
    @InjectMocks InventoryService inventoryService;

    private Product productWithStock(int stock) {
        Product product = new Product();
        product.setId(1L);
        product.setName("상품1");
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory);
        return product;
    }

    @Test
    void 재고를_증가시킨다() {
        Product product = productWithStock(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        int adjusted = inventoryService.adjust(1L, 5, "정기조사");

        assertThat(adjusted).isEqualTo(15);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(15);
    }

    @Test
    void 재고를_감소시킨다() {
        Product product = productWithStock(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        int adjusted = inventoryService.adjust(1L, -3, "파손");

        assertThat(adjusted).isEqualTo(7);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(7);
    }

    @Test
    void 재고가_음수가_되는_조정은_거부하고_재고를_보존한다() {
        Product product = productWithStock(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> inventoryService.adjust(1L, -11, "조정"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 없는_상품을_조정하면_EntityNotFoundException을_던진다() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.adjust(99L, 1, "조정"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }
}
