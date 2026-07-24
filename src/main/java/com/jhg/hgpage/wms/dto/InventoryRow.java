package com.jhg.hgpage.wms.dto;

/** 관리자 재고화면 행. */
public record InventoryRow(Long productId, String productName, int onHandQty) {}
