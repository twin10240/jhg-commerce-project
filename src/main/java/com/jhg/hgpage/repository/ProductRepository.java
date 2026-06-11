package com.jhg.hgpage.repository;

import com.jhg.hgpage.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByNameContainingIgnoreCase(String keyword, Pageable pageable);

    @Query("select p from Product p join fetch p.inventory")
    List<Product> findAllWithInventory();

    // 메인 상품 카드가 재고를 표시하므로 fetch join으로 N+1을 방지한다
    @Query(value = "select p from Product p join fetch p.inventory",
            countQuery = "select count(p) from Product p")
    Page<Product> findPageWithInventory(Pageable pageable);

    @Query(value = "select p from Product p join fetch p.inventory where lower(p.name) like lower(concat('%', :keyword, '%'))",
            countQuery = "select count(p) from Product p where lower(p.name) like lower(concat('%', :keyword, '%'))")
    Page<Product> findPageByNameWithInventory(@Param("keyword") String keyword, Pageable pageable);
}
