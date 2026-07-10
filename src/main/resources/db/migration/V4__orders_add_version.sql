-- V4: orders.version 컬럼 추가 — Order 낙관적 락(@Version) 도입 (2026-07-10)
--
-- [배경] Order 엔티티에 @Version(Long) 추가(취소 vs 백오더 승격 경합 방어, 리뷰 B5).
--   prod는 ddl-auto: validate라 orders 테이블에 version 컬럼이 없으면 기동 시 스키마 검증 실패.
-- [목표] orders에 version 컬럼 추가 + 기존 행 0으로 백필(낙관적 락 초기값).
--
-- PostgreSQL(prod) 전용. H2(로컬/테스트)는 Flyway 비활성 + ddl-auto라 무관.
-- inventory.version(V1)과 동일하게 nullable BIGINT로 둔다(Hibernate가 관리).

ALTER TABLE orders ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
UPDATE orders SET version = 0 WHERE version IS NULL;
