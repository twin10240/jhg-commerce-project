package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public List<Product> findAllWithInventory() {
        return productRepository.findAllWithInventory();
    }

    // keyword 가 비어있으면 전체 페이지, 있으면 이름(부분/대소문자무시) 검색 페이지
    public Page<Product> findPage(String keyword, Pageable pageable) {
        if (!StringUtils.hasText(keyword)) {
            return productRepository.findAll(pageable);
        }
        return productRepository.findByNameContainingIgnoreCase(keyword.trim(), pageable);
    }

}
