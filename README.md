# wallet-service

> **EN — At a glance.** `wallet-service` is the double-entry ledger and account
> leaf of the sportsbook system. It owns user balances split into
> `available` / `locked`, exposes idempotent transfer endpoints (deposit /
> withdraw / bet-debit / bet-payout), and guarantees the invariant *sum of all
> ledger entries equals zero*. It depends only on `shared-protocol` — no other
> service — and is called by `betting-service`, `settlement-service`, and
> `gateway`. Java 17, Spring Boot 3.2, PostgreSQL 16 (`SELECT FOR UPDATE` on
> the account row), Kafka with a transactional outbox, Redis for the
> idempotency fast path. Performance target: 5 000 RPS debit, p99 < 50 ms,
> zero double-spending under contention. See ADR-0003 / 0005 / 0006 / 0015 /
> 0016 in `orchestration/docs/architecture/decisions/`.

---

## 시스템에서의 위치

`wallet-service`는 9개 repo로 구성된 sportsbook 시스템의 **leaf 서비스**다. 다른
어떤 서비스에도 의존하지 않고 `shared-protocol`(공통 DTO/이벤트/value object)만
의존한다. 시스템에서 자금이 움직이는 모든 경로의 종착점이자 출발점이라서, 가장
보수적으로 다뤄지는 repo다.

```
┌────────────────────────────────────────────────────────────────┐
│ shared-protocol  ←── wallet-service (this repo)                │
│                          ↑                                     │
│      betting-service ────┘  (debit on bet placement)           │
│      settlement-service ──→ wallet (payout / refund)           │
│      gateway ──→ wallet (balance read)                         │
└────────────────────────────────────────────────────────────────┘
```

상세 의존성 그래프와 cross-cutting 결정은 상위 폴더의
[`sportsbook/CLAUDE.md`](../CLAUDE.md) 와 ADR 디렉터리를 참조한다.

## 책임 범위

**한다**:

- Account 생성 (사용자 1인당 1계좌, `available` + `locked` 잔고로 분리)
- 잔고 조회 (사용 가능 / 잠금 / 통화 단위로 명확)
- **Double-entry ledger** — 모든 잔고 변경은 차변/대변 쌍의 journal entry로 기록 (합 = 0)
- 자금 이동 트랜잭션:
  - `deposit` (외부 → 사용자)
  - `withdraw` (사용자 → 외부)
  - `debit` (베팅 stake — `available` → `locked`)
  - `credit` (정산 payout / 환불 — `locked` → `available`)
- 멱등성 (같은 `Idempotency-Key`로 N회 호출돼도 단 1회만 반영)
- 정합성 invariant 검증 (매 transaction 후 + 일일 reconciliation 배치)

**하지 않는다**:

- 베팅 비즈니스 로직 (slip 검증, 한도 검사는 betting-service / risk-service)
- 결제 PG 직접 연동 (V1은 mock — 추후 `payment-service` 분리 가능)
- 사용자 인증 (gateway 책임)

## 결정 사항 (요약)

ADR 색인은 [`orchestration/docs/architecture/decisions/`](../orchestration/docs/architecture/decisions/)
에서 본문 확인. 이 repo에 직접 적용되는 결정:

| 항목 | 결정 | 출처 |
|---|---|---|
| 스택 | Java 17 + Spring Boot 3.2.x + Maven | [ADR-0015](../orchestration/docs/architecture/decisions/0015-stack-pivot-to-java.md) |
| Money | `Money(long amount, Currency currency)` minor units | [ADR-0003](../orchestration/docs/architecture/decisions/0003-type-system-primitives.md) |
| Time | `Instant` UTC | [ADR-0003](../orchestration/docs/architecture/decisions/0003-type-system-primitives.md) |
| ID | UUID v7 | [ADR-0003](../orchestration/docs/architecture/decisions/0003-type-system-primitives.md) |
| API | `/internal/v1/wallet/*`, RFC 7807 ProblemDetail, camelCase | [ADR-0004](../orchestration/docs/architecture/decisions/0004-api-conventions.md) |
| DB | PostgreSQL 16 + Flyway, READ COMMITTED + `SELECT FOR UPDATE` on account | [ADR-0005](../orchestration/docs/architecture/decisions/0005-persistence-patterns.md) |
| 멱등성 | DB unique on `idempotency_key` + Redis SETNX 가속 | [ADR-0005](../orchestration/docs/architecture/decisions/0005-persistence-patterns.md) |
| 메시징 | Kafka + Avro + transactional outbox | [ADR-0006](../orchestration/docs/architecture/decisions/0006-messaging-and-saga.md) |
| 관측성 | JSON logback + Micrometer/OTel + Prometheus | [ADR-0007](../orchestration/docs/architecture/decisions/0007-observability.md) |
| 언어 | commit/code/주석/API 메시지 영어, 한국어는 docs/ | [ADR-0016](../orchestration/docs/architecture/decisions/0016-english-deliverable-policy.md) |

이 repo 고유 결정 (ADR 승격 후보):

- **다계좌** — `available` + `locked`을 분리해 open exposure를 명시. 보너스 계좌는 V2 후보.
- **잔고 = snapshot + 증분** — pure event sourcing은 read 비용이 비싸 매 잔고 조회마다 ledger 합산은 불가. snapshot column으로 O(1) read, 일일 reconciliation 배치로 audit.
- **invariant 이중** — 매 transaction 후 listener + 일일 배치. 위반 시 즉시 알람 + 운영자가 수동 검토 (자동 거래 정지는 V2).

## 의존

- 직접 의존: `com.sportsbook:shared-protocol:0.1.0-SNAPSHOT`
  - 빌드 전 `cd shared-protocol && mvn install`로 mavenLocal에 먼저 publish 필요
- 런타임 외부 시스템: PostgreSQL 16, Kafka, Redis (`load-test/docker-compose.yml`에 동일 구성)

## 빌드 / 실행 / 테스트

```sh
# (전제) shared-protocol publish
cd ../shared-protocol && mvn install -DskipTests

# 빌드 (compile + Spotless + Checkstyle 포함)
mvn verify

# Spring Boot 실행 (datasource/kafka/redis 환경변수는 application.yml 기본값 참조)
mvn spring-boot:run

# 단위/통합 테스트만
mvn test

# 통합 테스트는 Testcontainers로 PostgreSQL/Kafka/Redis를 컨테이너로 띄움
# → Docker Desktop 실행 필요
```

## 디렉터리 구조

```
wallet-service/
├── README.md                 (이 파일)
├── pom.xml                   Java 17 + Spring Boot 3.2.x + Spotless/Checkstyle
├── config/checkstyle/        Checkstyle 규칙 (shared-protocol 동일)
├── src/main/java/com/sportsbook/wallet/
├── src/main/resources/
│   ├── application.yml
│   ├── application-test.yml
│   └── db/migration/         Flyway SQL
├── src/test/java/com/sportsbook/wallet/
├── load-test/                k6 시나리오 + 결과 (Level 2/3 부하·정합성 증명)
└── docs/
    ├── commits/              ← retrospective 단계에서 작성
    ├── notes/                ← retrospective 단계에서 작성
    └── reflection/           ← retrospective 단계에서 작성
```

## 노출 인터페이스

모두 internal API (gateway/admin-api/betting-service 등 내부 호출 전용):

| Method | Path | 비고 |
|---|---|---|
| `POST` | `/internal/v1/wallet/accounts` | 사용자 계좌 생성 |
| `GET` | `/internal/v1/wallet/accounts/{userId}/balance` | 잔고 조회 |
| `POST` | `/internal/v1/wallet/transactions/debit` | 베팅 stake 차감 (`Idempotency-Key` 헤더 필수) |
| `POST` | `/internal/v1/wallet/transactions/credit` | 정산 payout / 환불 (`Idempotency-Key` 필수) |
| `POST` | `/internal/v1/wallet/transactions/deposit` | 입금 (`Idempotency-Key` 필수) |
| `POST` | `/internal/v1/wallet/transactions/withdraw` | 출금 (`Idempotency-Key` 필수) |

publish 이벤트 (Kafka, Avro):

- `wallet.debited.v1` — debit 성공
- `wallet.credited.v1` — credit/deposit/withdraw 성공
- `wallet.debit-failed.v1` — debit 실패 (InsufficientBalance 등)

Partition key는 `userId`.

## 성능 / 부하 테스트 목표

부하/증명 테스트는 `load-test/` 디렉터리에서 k6로 실행하고 결과를 `load-test/results/`에 박제한다.

| 시나리오 | 목표 | 위치 |
|---|---|---|
| 5000 RPS debit, 1분간 | p99 < 50ms, 에러율 < 0.1% | `load-test/scenarios/debit_load.js` |
| 동일 user 100 concurrent debit | double-spending 0건 | `load-test/scenarios/concurrency.js` |
| 같은 Idempotency-Key 100회 | 1건만 수락, 나머지 동일 응답 | `load-test/scenarios/idempotency.js` |

최고 성능 기록과 그래프는 `load-test/results/BEST.md` 참조.

## 제한 사항 (V1 스코프)

상위 [ADR-0012](../orchestration/docs/architecture/decisions/0012-v1-scope-decisions.md)의 의도적 제외 항목과 일치:

- **보너스 / free bet / promotion 계좌** — V2 후보
- **다국적 통화 환전 hot path** — KRW + USD 둘 다 지원하지만 사용자 계좌는 단일 통화 (cross-currency transfer 없음). 환율은 mock.
- **결제 PG 실연동** — `deposit` / `withdraw`는 mock external counterparty
- **multi-tenant 운영자 분리** — 단일 운영자 가정
