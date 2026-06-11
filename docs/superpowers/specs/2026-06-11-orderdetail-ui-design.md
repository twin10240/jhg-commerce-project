# 구매상세(orderdetail) 페이지 UI 개선 설계

날짜: 2026-06-11 / 상태: 승인됨

## 문제

1. `html,body{height:100%}` → 스크롤 영역에서 배경 그라데이션 반복(끊김).
2. 마크업이 의도한 2단 레이아웃의 `.grid-2`, `.section-gap`, `.page-title`, `.field`, `.label`, `.note`가
   전부 CSS에 미정의 → 카드가 스타일 없이 세로로 쌓임.
3. 주문자/배송지 폼이 상품 카드용 `.row`(1fr 128px 128px)를 재사용 → 필드가 찌그러짐.
4. main.html에서 복사된 미사용 CSS(상품 카드/탭/툴바 등)가 절반 이상.
5. '추가 정보'(배송메모/쿠폰/포인트)는 서버로 전송되지 않는 가짜 UI.

## 결정 사항 (사용자 선택 반영)

1. 배경: `html{height:100%}` + `body{min-height:100%}` + `background-repeat:no-repeat`.
2. 레이아웃: **2단 + sticky 요약** — 좌측 `1fr`(주문자→배송지→주문상품), 우측 `340px` 사이드바
   (결제금액→결제수단→약관→주문하기). 사이드바는 `position:sticky; top:24px`.
   ≤980px에서 1단 전환(사이드바 static).
3. 폼: `.field`(라벨+입력 세로), `.form-row.cols-3`(주문자 3등분),
   `.form-row.addr`(도시 1fr / 도로명 1.5fr / 우편번호 120px), ≤640px 1단.
4. '추가 정보' 섹션 + `applyPoint()` JS **제거**.
5. 미사용 CSS 제거, 다크 모드는 남는 요소 기준으로 보완.

## 불변 조건

- 폼 바인딩(`member.*`, `delivery.*`, `product[i].id/quantity`), CSRF, 검증 에러 표시 유지.
- 수량 변경 시 합계 재계산 JS 유지.
- 검증: `OrderControllerMvcTest`가 orderdetail 뷰를 실제 렌더링(빈 주문/수량 0 케이스)하므로
  템플릿 오류는 기존 테스트로 검출.
