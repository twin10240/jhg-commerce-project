package com.jhg.hgpage.wms.dto;

/** 관리자 재고화면 행: WMS는 상품명·가격을 모르므로 productId와 보유수량만 노출한다. */
public record InventoryRow(Long productId, int onHandQty) {}
