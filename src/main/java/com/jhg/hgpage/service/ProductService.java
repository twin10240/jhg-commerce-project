package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.repositoey.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    public List<Product> findAll() {
        return productRepository.findAll();
    }

}
