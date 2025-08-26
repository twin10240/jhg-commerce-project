package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.repositoey.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ProductServiceTest {

    @Autowired ProductRepository productRepository;

    @Test
    @Transactional
    @Rollback(value = false)
    public void createProductTest() {
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(100);
        inventory.setReservedQty(0);

        Product product = new Product();
        product.setName("샤프");
        product.setPrice(1000);
        product.setInventory(inventory);

        Product product1 = productRepository.save(product);

        System.err.println(product1.getId());
        System.err.println(product1.getName());
        System.err.println(product1.getPrice());
        System.err.println(product1.getInventory().getOnHandQty());
    }

}