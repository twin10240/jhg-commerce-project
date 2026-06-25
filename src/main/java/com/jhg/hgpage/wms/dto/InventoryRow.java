package com.jhg.hgpage.wms.dto;

/** 관리자 재고화면 행: 카탈로그(id/name/price) + WMS 보유수량(onHandQty)을 합친 뷰 DTO. */
public record InventoryRow(Long id, String name, int price, int onHandQty) {}
