package com.jhg.hgpage.catalog;

import com.jhg.hgpage.contract.InventoryQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    // 가용수량은 WMS 재고를 객체 그래프로 들추지 않고 조회 포트로만 얻는다(OMS→WMS 정상 방향)
    private final InventoryQueryPort inventoryQueryPort;

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public List<Product> findAllWithInventory() {
        return productRepository.findAllWithInventory();
    }

    /**
     * 메인 상품 그리드: 카탈로그 페이지(id/name/price)에 가용수량을 합쳐 카드로 반환한다.
     * keyword가 비어있으면 전체, 있으면 이름(부분/대소문자무시) 검색. 가용수량은
     * {@link InventoryQueryPort}로 상품 id 묶음을 한 번에 조회한다(라인별 N+1 회피).
     */
    public Page<ProductCardDto> findCardPage(String keyword, Pageable pageable) {
        Page<Product> page = StringUtils.hasText(keyword)
                ? productRepository.findByNameContainingIgnoreCase(keyword.trim(), pageable)
                : productRepository.findAll(pageable);

        List<Long> productIds = page.getContent().stream().map(Product::getId).toList();
        Map<Long, Integer> availableByProductId = inventoryQueryPort.availableByProductIds(productIds);

        return page.map(p -> new ProductCardDto(
                p.getId(), p.getName(), p.getPrice(),
                availableByProductId.getOrDefault(p.getId(), 0)));
    }

}
