-- V3: WMS 물리 분리(S1~S2) 반영 — 재고·예약·발주는 WMS DB가 단일 진실 공급원 (2026-07-08)
-- 주의: 이 마이그레이션 이후 S1 이전 OMS 빌드로 롤백 불가(테이블 소멸).
-- 데이터 이관 없음(데모 DB 결정) — WMS prod는 자체 시드(productId 1~20)로 시작.
DROP TABLE IF EXISTS purchase_order_item;  -- FK 자식 먼저
DROP TABLE IF EXISTS purchase_order;
DROP TABLE IF EXISTS reservation;
DROP TABLE IF EXISTS inventory;
DROP SEQUENCE IF EXISTS purchase_order_item_seq;
DROP SEQUENCE IF EXISTS purchase_order_seq;
DROP SEQUENCE IF EXISTS reservation_seq;
DROP SEQUENCE IF EXISTS inventory_seq;
