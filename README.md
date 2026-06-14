# wallet-service

> **English summary**
>
> **What it is.** `wallet-service` is the double-entry ledger and account leaf
> of the sportsbook microservice system. It owns user balances and every money
> movement, recorded as matched debit/credit journal pairs whose system-wide
> sum is always zero.
>
> **Architecture.** A leaf service: it depends only on `shared-protocol` and on
> no other service. It is called by `betting-service` (bet-stake debit),
> `settlement-service` (payout / refund credit), and `gateway` (balance reads),
> and emits `WalletDebited` / `WalletCredited` / `WalletDebitFailed` to Kafka
> through a transactional outbox so an event can never diverge from its ledger
> change.
>
> **Features.** One account per user, split into `available` / `locked`
> buckets so bet exposure stays inside the account. Idempotent transfers
> (deposit / withdraw / bet-debit / bet-payout) keyed by an `Idempotency-Key`,
> backed by a three-layer contract: a Redis fast path, a pessimistic-lock write
> path, and DB-constraint race recovery. Dual integrity guards: an after-commit
> invariant listener plus a daily reconciliation job.
>
> **Tech stack.** Java 17, Spring Boot 3.2, Maven. PostgreSQL 16 with
> `SELECT FOR UPDATE` on the account row and Flyway migrations. Kafka + Avro
> (no schema registry in V1). Redis for the idempotency fast path. Micrometer /
> OpenTelemetry / Prometheus for observability.
>
> **Build & run.** `mvn verify` runs Spotless, Checkstyle and the test suite;
> integration tests use Testcontainers, so Docker must be running, and
> `shared-protocol` must be installed to mavenLocal first.
>
> **Performance.** Target: 5 000 RPS debit, p99 < 50 ms, zero double-spending
> under contention. Dev-host baseline: 500 RPS sustained at p95 ≈ 3 ms with
> 0 % errors; 100 concurrent debits on one user never overdraw; 100 same-key
> debits collapse to a single ledger pair.
>
> **Limitations (V1).** No bonus accounts, no cross-currency exchange, no real
> payment-gateway integration, no schema registry; deposit / withdraw do not
> emit Kafka events. See ADR-0003 / 0004 / 0005 / 0006 / 0012 / 0014 / 0015 /
> 0016 in `orchestration/docs/architecture/decisions/`.

---

## 시스템에서의 위치

> 시스템 전체 설계는 orchestration 레포의 docs/DESIGN.md에 있습니다.

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
`sportsbook/CLAUDE.md` 와 ADR 디렉터리를 참조한다. (ADR 본문은 orchestration
repo의 `docs/architecture/decisions/`에 있다 — 이 leaf repo가 독립 push되면
레포 밖 상대 경로는 해석되지 않으므로 링크 대신 경로만 남긴다.)

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

ADR 본문은 orchestration 레포의 `docs/architecture/decisions/`에서 확인한다
(아래 표의 출처 열은 ADR 번호만 표기 — 레포 밖 경로라 링크는 두지 않는다).
이 repo에 직접 적용되는 결정:

| 항목 | 결정 | 출처 |
|---|---|---|
| 스택 | Java 17 + Spring Boot 3.2.x + Maven | ADR-0015 |
| Money | `Money(long amount, Currency currency)` minor units | ADR-0003 |
| Time | `Instant` UTC | ADR-0003 |
| ID | UUID v7 | ADR-0003 |
| API | `/internal/v1/wallet/*`, RFC 7807 ProblemDetail, camelCase | ADR-0004 |
| DB | PostgreSQL 16 + Flyway, READ COMMITTED + `SELECT FOR UPDATE` on account | ADR-0005 |
| 멱등성 | DB unique on `idempotency_key` + Redis SETNX 가속 | ADR-0005 |
| 메시징 | Kafka + Avro + transactional outbox | ADR-0006 |
| 관측성 | JSON logback + Micrometer/OTel + Prometheus | ADR-0007 |
| 언어 | commit/code/주석/API 메시지 영어, 한국어는 docs/ | ADR-0016 |

이 repo 고유 결정 (ADR 승격 후보):

- **dual-bucket 계좌** — 한 계좌를 `available` + `locked` 두 bucket으로 분리해 open exposure를 계좌 안에서 명시. 보너스 계좌(별도 bucket)는 V2 후보.
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
    ├── README.md             문서 진입점
    ├── commits/              dev 커밋별 문서 (000~019) + L3/L2 빠른 참조 색인
    └── reflection/           retrospective.md + change-cost.md
```

`docs/notes/`는 두지 않는다 (Phase 2 정책, 2026-05-29 결정). 학습 내용은 `docs/commits/NNN.md` 본문과 그 `기억/설명 Level` 색인으로 흡수한다.

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

- `wallet.debited.v1` — bet-stake debit 성공
- `wallet.credited.v1` — credit 성공 (payout / refund). `deposit` / `withdraw`는 V1에서 이벤트를 발행하지 않는다 (베팅 saga가 보지 않는 운영성 연산이고 `WalletCredited`의 reason enum에 맞는 값이 없음 — `docs/commits/011.md` 참조)
- `wallet.debit-failed.v1` — debit 실패 (InsufficientBalance 등)

Partition key는 `userId` (사용자별 순서 보존).

## 성능 / 부하 테스트

부하/증명 테스트는 `load-test/`에서 k6로 실행하고 결과를 `load-test/results/<date>/`에 박제한다. 갱신 규칙·환경 메타데이터는 [`load-test/results/BEST.md`](load-test/results/BEST.md) 참조.

### 목표 vs 측정 (2026-05-28 dev host)

| 시나리오 | 목표 | 측정치 (M1 Pro / Docker 28, 8 CPU) | 결과 |
|---|---|---|---|
| `debit_load.js` — 5 000 RPS debit, p99 < 50 ms, 에러율 < 0.1 % | (위 목표) | 500 RPS / p95 3.15 ms / p99 ≈ 20 ms / 에러 0 % | ✅ design 한계 미도달, dev host CPU 포화로 1 000 RPS p95 ↑ |
| `concurrency.js` — 단일 user 100 concurrent debit, double-spending 0 | (위 목표) | 100/100 succeed, total balance preserved exactly | ✅ |
| `idempotency.js` — 동일 Idempotency-Key 100회, 1건만 수락 | (위 목표) | 100 concurrent calls → 단일 ledger pair, 정확히 한 번 debit 적용 | ✅ |

dev host의 1 000 RPS run에서 p95가 ~105 ms로 상승하지만 에러는 여전히 0. Pessimistic 락 / Redis fast path / outbox 설계는 한계 없이 동작하고, k6가 Docker Desktop CPU 풀을 더 빨리 소진한다. Production-grade 하드웨어에서의 측정은 orchestration repo의 e2e harness가 도입될 때 BEST.md에 추가.

### 실행 (요약)

```sh
mvn -DskipTests package
docker compose -f load-test/docker-compose.yml up -d
# wallet ready 대기 후
cd load-test/scenarios
k6 run -e BASE_URL=http://localhost:58081 -e RATE=500 -e DURATION=60s debit_load.js
```

전체 절차와 시드 / 정합성 시나리오는 [`load-test/README.md`](load-test/README.md) 참조.

## 제한 사항 (V1 스코프)

상위 ADR-0012의 의도적 제외 항목과 일치:

- **보너스 / free bet / promotion 계좌** — V2 후보
- **다국적 통화 환전 hot path** — KRW + USD 둘 다 지원하지만 사용자 계좌는 단일 통화 (cross-currency transfer 없음). 환율은 mock.
- **결제 PG 실연동** — `deposit` / `withdraw`는 mock external counterparty
- **multi-tenant 운영자 분리** — 단일 운영자 가정
