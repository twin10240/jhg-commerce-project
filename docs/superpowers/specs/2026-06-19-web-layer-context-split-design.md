# web 계층 컨텍스트 분리 (Phase 2 잔여 마무리)

작성일: 2026-06-19
대상: `com.jhg.hgpage` web 계층(컨트롤러·폼·뷰DTO)을 OMS/WMS 바운디드 컨텍스트로 수직 분할

## 배경 / 목적

코어(domain·repository·service)는 2026-06-18에 `contract`/`catalog`/`oms`/`wms`로 수직 분할
완료됐으나, 컨트롤러·폼·뷰DTO는 아직 공용 `controller/`·`api/`·`domain/dto/view/`에 OMS·WMS가
섞여 있다. 특히 `AdminController` 한 클래스가 배송완료(OMS)와 재고조정·발주입고(WMS)를 함께
들고 있어, Phase 3(WMS 물리 분리)에서 한 클래스를 반으로 쪼개 떼어내야 하는 상태다.

이 작업은 web 계층도 코어와 **동일한 수직 슬라이스 원칙**으로 맞춰, Phase 3에서 `wms/`를
폴더째 들어내면 WMS 웹까지 따라가도록 경계를 명확히 한다.

**순수 이동 — 동작·DB·화면·URL 전부 불변.** 기존 테스트가 회귀 안전망.

## 비목표 (YAGNI)

- 동작/정책 변경 없음. 기능 추가 없음.
- `MainController`를 컨텍스트로 쪼개지 않음 — catalog 그리드 + OMS 주문 + WMS 재고를 한
  화면(`main.html`)에 조립하는 BFF(화면 조립) 성격이라 공용 계층에 남기는 게 자연스럽다.
- `UserPrincipal`·`Role` 이동 없음(아래 결정 D2).

## 목표 패키지 구조

```
web/                      (공용/교차 — 신설)
  ├ HomeController        (/)
  └ MainController        (/main — catalog+OMS+WMS 조립)

oms/
  ├ domain/ repository/ service/      (이미 분리됨)
  ├ dto/                              ← 응답 DTO (신설, 결정 D1)
  │   OrderDto, OrderDetailDto, CartItemDto, AdminOrderDto
  └ web/
      ├ controller/  AuthController, CartController, OrderController, OrderAdminController
      ├ api/         CartApiController, OrderApiController
      └ form/        SignUpForm, CheckOutForm, OrderRequest

wms/
  ├ domain/ repository/ service/      (이미 분리됨)
  └ web/
      ├ controller/  InventoryAdminController
      └ form/        PurchaseOrderForm

domain/dto/   UserPrincipal     (그대로 — 결정 D2)
domain/enums/ Role              (그대로 — 인증 공용)
```

## 설계 결정

### D1. 응답 DTO는 `oms/web/dto`가 아니라 `oms/dto`

`CartItemDto`는 `CartRepositoryQuery`(=`oms/repository`)가 QueryDSL `@QueryProjection`으로
**생성**한다. DTO를 `oms/web/dto`에 두면 **repository → web**(아래 계층이 위 계층을 참조)이라는
나쁜 방향 의존이 생긴다. 따라서 응답 DTO는 web 밑이 아니라 컨텍스트 레벨 `oms/dto`에 둬서
repository·service·web 셋 다 자연스럽게 참조하게 한다.

반면 **폼(`CheckOutForm`/`OrderRequest`/`SignUpForm`)은 HTTP 입력 바인딩 전용**이라 web 계층
소속이 맞아 `oms/web/form`에 둔다. (응답 DTO ≠ 입력 폼, 소속이 다르다)

### D2. `UserPrincipal`·`Role`은 안 옮김

`UserPrincipal`은 `AccountService.loadUserByUsername`(service)·`SecurityConfig`·모든 컨트롤러가
쓰는 Spring Security 주체(인증 인프라)다. web 전용이 아니므로(web으로 내리면 service→web
역참조 발생) 공용 `domain/dto`에 유지. `Role`도 인증 공용이라 `domain/enums` 유지.

### D3. AdminController 분할

| 새 컨트롤러 | 위치 | 매핑 | 의존 |
|---|---|---|---|
| `OrderAdminController` | `oms/web/controller` | `/admin/orders`, `/admin/orders/complete-delivery` | `OrderService` |
| `InventoryAdminController` | `wms/web/controller` | `/admin/inventory`, `/admin/inventory/adjust`, `/admin/purchase-orders`, `/admin/purchase-orders/receive` | `InventoryAdjustmentService`, `PurchaseOrderService`, `ProductService`(catalog=공용) |

URL은 그대로라 화면·`SecurityConfig` 영향 없음. WMS 컨트롤러가 catalog `ProductService`에
의존하는 것은 허용(catalog는 OMS·WMS 공통 참조).

## 영향 / 안전성

- **템플릿 수정 0건**: 템플릿의 유일한 FQN 참조는 `main.html`의
  `T(com.jhg.hgpage.oms.domain.enums.OrderStatus)`인데 이건 안 옮긴다. 뷰DTO·폼은 속성명으로만
  참조돼 패키지 이동에 영향 없음.
- **Spring 컴포넌트 스캔**: 루트 `com.jhg.hgpage` 기준이라 설정 변경 불필요.
- **QueryDSL**: `QCartItemDto`는 빌드 시 새 패키지(`oms/dto`)로 재생성.
- **테스트 11개 파일** import 갱신 필요:
  `OrderServiceTest`, `OrderServiceDetailTest`, `OrderServiceAdminTest`, `CartServiceTest`,
  `OrderControllerTest`, `OrderControllerMvcTest`, `MainControllerMvcTest`,
  `AuthControllerMvcTest`, `AdminControllerMvcTest`, `OrderApiControllerMvcTest`,
  `CartApiControllerMvcTest`.
- `AdminControllerMvcTest`는 `OrderAdminControllerMvcTest`(oms) +
  `InventoryAdminControllerMvcTest`(wms)로 분할(슬라이스 테스트는 컨트롤러 클래스를 타깃하므로).

## 실행 슬라이스 (각 단계 `gradlew build` green 유지)

1. 응답 DTO → `oms/dto` (+ `CartRepositoryQuery` import 갱신, `QCartItemDto` 재생성)
2. 폼 → `oms/web/form`, `wms/web/form`
3. OMS 컨트롤러·api → `oms/web/{controller,api}`
4. `AdminController` 분할 → `OrderAdminController`(oms) + `InventoryAdminController`(wms)
5. Home·Main → 공용 `web/`
6. 테스트 11개 import 갱신 + `AdminControllerMvcTest` 분할

순수 이동이라 TDD(RED→GREEN) 아님 — 기존 전체 테스트가 안전망. 각 슬라이스 후 빌드 green 확인.

## 완료 기준

- 위 목표 패키지 구조대로 클래스 배치 완료.
- `AdminController` 제거, 두 컨트롤러로 분할 완료.
- `gradlew build` 전체 테스트 green(현재 171건 수준 유지).
- 동작·DB·화면·URL 불변(회귀 없음).
