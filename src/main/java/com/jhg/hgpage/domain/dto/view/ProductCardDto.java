package com.jhg.hgpage.domain.dto.view;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 메인 상품 그리드 카드 응답 DTO.
 *
 * <p>카탈로그(id/name/price)에 WMS의 판매 가용 수량을 합친 읽기 전용 뷰다. 화면이 이 DTO만 보고
 * {@code Product → Inventory} 객체 그래프를 직접 들추지 않게 해, OMS 판매 화면과 WMS 재고의 결합을 끊는다.
 * {@code availableQty}는 {@link com.jhg.hgpage.service.InventoryQueryPort}로 조회한 값이다.
 */
@Getter
@AllArgsConstructor
public class ProductCardDto {
    private Long id;
    private String name;
    private int price;
    private int availableQty;
}
