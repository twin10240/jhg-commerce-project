# 실시간 알림 구현 로드맵

**Spec:** `docs/superpowers/specs/2026-08-30-realtime-notification-service-design.md`

구현은 저장소와 검토 경계를 기준으로 다음 세 계획으로 나눈다.

1. `2026-08-30-realtime-notification-oms-foundation.md`
   - OMS 연결 JWT, 트랜잭셔널 Outbox, HMAC 발행기, 업무 이벤트 연결
2. `2026-08-30-realtime-notification-node-service.md`
   - 별도 NestJS 저장소, PostgreSQL, 이벤트 수신, 알림 API, Socket.IO
3. `2026-08-30-realtime-notification-oms-ui.md`
   - OMS 헤더 알림 버튼, 최근 알림, 알림함, 재연결과 읽음 처리

## 실행 순서

```text
OMS Foundation Tasks 1-3
  -> Node Service 전체
  -> OMS Foundation Tasks 4-6
  -> OMS UI 전체
  -> 양쪽 통합·장애 복구 시나리오
```

OMS Outbox 모델과 JWT 계약을 먼저 만들어 Node 서비스가 사용할 계약을 고정한다. Node 서비스가 내부 이벤트와 사용자 API를 제공한 뒤 OMS 발행기와 화면을 연결한다. 각 계획은 자기 저장소에서 독립 커밋하며, 마지막 통합 검증 전까지 기존 주문 처리 경로는 실시간 서비스의 가용성에 의존하지 않는다.

## 완료 게이트

- OMS 전체 `./gradlew test`
- Node 전체 `npm test` 및 `npm run test:e2e`
- Node `npm run lint`, `npm run build`
- `diff` OMS `docs/contracts/realtime-event-v1.json` and Node `test/contracts/realtime-event-v1.json`
- 실시간 서비스 중단 중 주문 처리 후 재기동 시 알림 한 건 생성
- 다른 사용자의 JWT로 알림 조회·읽음 불가
- JWT 만료 후 자동 재발급·재연결
- 읽지 않은 알림을 포함해 90일 경계 삭제
