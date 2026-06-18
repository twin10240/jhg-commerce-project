package com.jhg.hgpage.wms.repository;

import com.jhg.hgpage.wms.domain.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    // 관리자 발주 현황 화면용: 품목/상품까지 한 번에 로드(최신순)
    @Query("select distinct po from PurchaseOrder po " +
            "left join fetch po.items i " +
            "left join fetch i.product " +
            "order by po.id desc")
    List<PurchaseOrder> findAllWithItems();
}
