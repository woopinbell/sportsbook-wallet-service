# Change Cost Simulation

`wallet-service`가 V1 범위를 넘어 운영에서 살아남는다고 가정하면, 6-12개월 안에 들어올 변경 요청은 대체로 다섯 갈래다. 보너스 잔고 추가, 입출금 이벤트 발행, cross-currency 환전, reconciliation 성능, 단일 사용자 동시 처리량이다. 각 요청이 어느 결정과 충돌하고 어디가 깨지는지를 미리 짚어둔다. 핵심 데이터 모델(`Account` 2-bucket, `LedgerEntry` matched pair, sum-zero invariant)과 동시성 정책(비관적 락)이 대부분의 변경에서 충돌 지점이 된다.

## 변경 시나리오 표

| 변경 요청 | 깨질 위치 | 복구 동선 | 비용 추정 |
|----------|----------|----------|----------|
| 보너스(bonus) 잔고 bucket 추가 | `Account`의 `available`/`locked` 2-bucket 고정 구조와 두 `@Embedded EmbeddedMoney`. `BalanceBucket` enum(`AVAILABLE`/`LOCKED`만). `V1__account_and_ledger.sql`의 `account` 컬럼 집합과 `account_currency_match` CHECK. `WalletService`의 leg 매핑 | `BalanceBucket`에 `BONUS` 추가, Flyway `V3`로 `bonus_amount`/`bonus_currency` 컬럼과 CHECK 확장, `Account`에 bonus mutator 추가. 베팅 차감 우선순위(보너스 우선 소진인지 available 우선인지)를 정책으로 결정하고 `debit` leg에 반영 | 3-5일 |
| deposit/withdraw 이벤트 발행 | `WalletEventFactory`가 `debited`/`credited`/`debitFailed` 세 가지만 만든다. shared-protocol `WalletCredited`의 reason enum(`PAYOUT`/`VOID`/`REFUND`)에 입출금에 맞는 값이 없다. `WalletService.deposit`/`withdraw`가 outbox에 쓰지 않는다 | hub 세션에서 shared-protocol에 `WalletDeposited`/`WalletWithdrawn` Avro 스키마 추가, 의존 repo 재빌드. `WalletEventFactory`에 팩토리 메서드와 토픽 상수 추가, `deposit`/`withdraw` 트랜잭션 안에서 `outboxRepo.save(...)` 호출 | 2-3일 (shared-protocol 변경 + 다운스트림 sync 별도) |
| cross-currency 환전(KRW↔USD) | 한 계좌 한 통화 가정. `account_currency_match` CHECK, `Account.requireSameCurrency`, shared-protocol `Money.add`/`subtract`의 same-currency 강제. sum-zero invariant가 단일 통화 전제 | 환율 소스 도입(mock 이상), 환전 전송을 출금 통화 entry와 입금 통화 entry 두 쌍으로 모델링하고 환율 차익을 house 계좌로 흡수. `InvariantChecker`/`DailyReconciliationJob`의 합 = 0 검증을 기준 통화 환산 또는 통화별 분리로 재정의 | 1-2주 (invariant 재정의가 가장 큼) |
| reconciliation이 대량 계좌에서 느려짐 | `DailyReconciliationJob.reconcile()`의 `accountRepo.findAll()` 전체 순회. 행이 많아지면 read 트랜잭션이 길게 잡힌다 | `findAll(Pageable)` 청크 루프로 바꾸고 청크별 부분 합산 후 집계. `netSumAll`은 이미 DB 집계 쿼리라 그대로 두고 per-account 검증만 페이지네이션 | 0.5-1일 |
| 단일 사용자 동시 debit 처리량 부족 | `AccountRepository.findByUserIdForUpdate`의 row lock이 한 사용자의 모든 debit을 직렬화한다. ADR-0005가 재논의 트리거로 명시한 지점 | 한 계좌 잔고를 N개 sub-balance 행으로 분할(lock segmentation)하거나 잔고를 event sourcing으로 옮겨 락 자체를 제거. 둘 다 `Account` 집합체와 스냅샷 모델의 재설계 | 2-3주+ |

## 의도적으로 미룬 진화

cash out(베팅 중 조기 정산), 보너스/프로모션 계좌, multi-tenant 운영자 분리, 결제 PG 실연동, Confluent/Apicurio Schema Registry는 다루지 않았다. 앞의 셋은 ADR-0012가 V1 scope에서 명시적으로 닫은 항목이고, 결제 PG는 `EXTERNAL_PAYMENT` 시스템 계좌로 모킹해 자금 흐름의 모양만 잡아뒀다. Schema Registry는 ADR-0014가 V2로 미뤘고, 그전까지는 producer와 consumer가 shared-protocol의 같은 Avro 생성 클래스를 고정 버전으로 공유하는 것으로 스키마 합의를 대신한다.

이 결정들의 공통점은 현재 구조 위에 기능을 얹는 형태라 핵심 데이터 모델을 깨지 않는다는 점이다. 보너스는 bucket 추가, cash out은 새 credit reason, Schema Registry는 직렬화 계층 교체로 각각 국소적으로 들어온다.

## 재설계가 합리적인 임계점

다음 두 임계점 중 하나라도 들어오면 부분 수정보다 저장 계층을 다시 잡는 쪽이 합리적이다.

첫째, 단일 PostgreSQL 인스턴스의 쓰기 처리량이 한계에 닿는 경우다. 전체 debit이 수만 RPS를 넘거나 인기 사용자 한 명에게 초당 수십 건 이상이 몰리면, per-user row lock이 직렬화 병목이 된다. 이 시점에는 잔고를 event sourcing으로 옮겨 append-only 쓰기로 바꾸거나, 계좌를 샤딩(sharding)하는 재설계가 필요하다. ADR-0005도 이 경우를 락 segmentation 또는 event sourcing 검토 트리거로 적어뒀다.

둘째, `ledger_entry` 테이블이 수십억 행 규모로 커지는 경우다. append-only라 행은 단조 증가하고, `(account_id, created_at)` 인덱스만으로는 reconciliation과 audit 쿼리가 버티지 못하는 지점이 온다. 이때는 시간 기준 파티셔닝(partitioning)과 오래된 분개의 콜드 스토리지 아카이빙을 도입하고, 스냅샷을 일·월 단위 체크포인트로 물질화(materialize)하는 쪽으로 옮긴다.

두 임계점 모두 "한 계좌 = 한 row, 잔고는 스냅샷 + 증분"이라는 1차 결정 자체를 무너뜨린다. 그 시점에는 REST 인터페이스(`/internal/v1/wallet/*`)와 이벤트 계약(shared-protocol Avro)만 유지하고 내부 저장 계층을 교체하는 쪽이 다운스트림 영향을 가장 작게 만든다.
