# Retrospective

`wallet-service`는 sportsbook 시스템의 9개 repo 중 돈을 직접 다루는 leaf 서비스다. 이 문서는 dev 단계(커밋 22개)를 끝낸 뒤의 회고다. 마지막 한 커밋(`95b8a60` forfeit)은 Phase 5 전체 스택 통합에서 드러난 LOST 정산 leg를 채운 것이다. 사실 근거는 `main`의 커밋 메시지 본문과 실제 코드 diff, 그리고 부하 테스트 결과(`load-test/results/BEST.md`)다.

## 무엇을 만들었나

사용자 잔고를 double-entry ledger로 관리하는 서비스를 만들었다. 핵심은 다섯 덩어리다.

첫째, `Account` 집합체. 사용자당 하나의 계좌를 `available`(사용 가능)과 `locked`(베팅 노출에 묶인 금액) 두 bucket으로 나눠 들고, 모든 잔고 변경은 의도 이름이 붙은 mutator(`moveAvailableToLocked`, `forfeitLocked` 등)를 거친다.

둘째, `LedgerEntry` append-only 분개장. 모든 자금 이동은 `LedgerEntry.pair()`가 만드는 차변(debit)/대변(credit) 쌍으로 기록되고, 두 entry는 같은 `operation_group_id`와 `idempotency_key`를 공유한다. 시스템 전체에서 차변 합에서 대변 합을 뺀 값은 항상 0이다.

셋째, `WalletService`. deposit / withdraw / debit / credit / forfeit 다섯 연산이 하나의 멱등 전송 primitive를 공유한다. Redis fast path, 비관적 락(pessimistic lock) 기반 write path, DB unique 제약에 걸린 race-loser 복구 path 세 층으로 멱등성(idempotency)을 보장한다. (`forfeit`는 LOST 정산의 wallet leg로 Phase 5 통합에서 마지막에 배선됐다 — `BET_FORFEIT`로 진 스테이크를 user.locked에서 house로 capture한다.)

넷째, transactional outbox. 잔고를 바꾸는 트랜잭션과 같은 커밋 안에서 Kafka 이벤트(`WalletDebited` / `WalletCredited` / `WalletDebitFailed`)를 Avro 바이트로 outbox 테이블에 쓰고, 스케줄러가 별도로 Kafka에 흘려보낸다.

다섯째, 정합성 이중 장치. 매 커밋 직후 도는 `InvariantChecker`(after-commit listener)와 매일 새벽 도는 `DailyReconciliationJob`이 ledger 합 = 0과 스냅샷-원장 일치를 검증한다.

REST 표면은 `/internal/v1/wallet/*` 7개 endpoint이고 오류는 RFC 7807 ProblemDetail로 돌려준다. 단위/통합 테스트 49개가 통과하고, 부하 테스트는 dev host 기준 debit 500 RPS에서 p95 3.15ms, 에러율 0%를 기록했다. 100건 동시 debit에서 double-spending 0건, 같은 Idempotency-Key 100건 동시 호출에서 ledger pair 정확히 1개를 증명했다.

V1에서 의도적으로 닫은 범위는 보너스 계좌, cross-currency 환전, 결제 PG 실연동, Schema Registry다.

## 시작 시점의 가설

가장 어려울 거라고 본 곳은 동시성이었다. 한 사용자에게 동시에 debit이 쏟아질 때 double-spending을 어떻게 막을지, 같은 Idempotency-Key로 N번 호출돼도 한 번만 반영되게 어떻게 보장할지가 이 repo의 본질이라고 생각했다. C/C++에서 mutex와 atomic을 다뤄봤기에 "락으로 직렬화한다"는 개념 자체는 익숙했고, 문제는 그것을 PostgreSQL row lock과 JPA 위에 어떻게 얹느냐의 번역 문제일 거라고 봤다.

반대로 가볍게 본 곳이 세 군데였다. JPA entity 매핑은 어노테이션 몇 개 붙이면 끝일 거라고 봤다. transactional outbox는 테이블 하나에 행 넣고 스케줄러가 읽으면 되는 단순 패턴이라고 봤다. `@Transactional`은 메서드에 붙이면 알아서 트랜잭션을 열고 닫아줄 거라고 봤다.

ledger 도메인 자체(차변/대변, 합 = 0)는 회계의 표준이라 설계 부담이 없을 거라 봤고 실제로도 그랬다.

## 가설 vs 실제

동시성은 예상보다 수월했다. ADR-0005가 이미 "critical path는 `SELECT FOR UPDATE`, 멱등성은 DB unique + Redis 가속"을 박아둔 덕분에 구현은 결정을 코드로 옮기는 작업에 가까웠다. `findByUserIdForUpdate`에 `@Lock(PESSIMISTIC_WRITE)`를 걸고, 100개 동시 debit을 `CompletableFuture`로 쏘는 테스트(`e8ac469`)가 한 번에 통과했다. C/C++의 mutex와 다른 점은 락의 단위가 코드 블록이 아니라 DB row이고, 락 해제가 트랜잭션 커밋에 묶인다는 점이었는데, 이 모델은 오히려 명시적이라 추론하기 쉬웠다.

정작 시간을 잃은 곳은 가볍게 봤던 세 곳과, 예상 못 한 프레임워크 관례였다.

첫째, 멱등성의 matched-pair 충돌. ADR-0005는 `idempotency_key`에 unique 제약을 걸라고 했지만, double-entry는 한 연산이 같은 키를 가진 두 행(차변/대변)을 만든다. 단순 `UNIQUE(idempotency_key)`는 쌍의 두 번째 행을 거부한다. `UNIQUE(idempotency_key, side)`로 튜플에 side를 넣어서야 "키당 차변 1개 + 대변 1개"가 성립하면서 동시에 재시도를 막는다는 결론에 도달했다(`62d6247`, `ec94e6d`). ADR을 읽을 때는 보이지 않던 충돌이 double-entry 구조와 만나서야 드러났다.

둘째, `@Transactional`의 함정. race-loser 복구 path는 `DataIntegrityViolationException`을 잡은 뒤 이긴 쪽의 pair를 다시 읽어야 한다. 그런데 메서드 레벨 `@Transactional` 안에서 unique 위반이 나면 그 트랜잭션은 rollback-only로 표시되고, 같은 트랜잭션 안에서 추가 read를 하면 `TransactionSystemException`이 난다. 결국 `TransactionTemplate`으로 프로그래밍 방식 트랜잭션 경계를 잡아서 catch 블록이 실패한 write 트랜잭션 바깥에서 돌도록 해야 했다(`92c6564`). 선언적 트랜잭션을 "그냥 붙이면 되는 것"으로 본 가설이 깨진 지점이다.

셋째, JPA가 외부 모듈의 record를 `@Embeddable`로 받지 못했다. shared-protocol의 `Money`는 Java 17 record인데, Hibernate 6은 다른 모듈의 record를 `@Embeddable`로 직접 바인딩하지 못한다. 영속성 경계 전용으로 `EmbeddedMoney` wrapper를 따로 만들고, 도메인 API는 여전히 `Money`를 주고받게 했다(`34c59e6`). C/C++이라면 그냥 struct를 재사용했을 자리에서, 프레임워크의 reflection 기반 매핑 제약 때문에 어댑터 한 겹이 필요했다.

넷째, 이벤트 디스패치 타이밍. `InvariantChecker`를 `@TransactionalEventListener(AFTER_COMMIT)`로 달았는데, 통합 테스트에서 합성 그룹을 직접 검증하려 하니 리스너가 한 번도 fire하지 않았다. AFTER_COMMIT 리스너는 감싸는 트랜잭션이 없으면 디스패치 자체를 하지 않는다. 테스트에서는 `verify()`를 직접 호출하고, AFTER_COMMIT 배선은 정상 debit 경로 테스트로 따로 검증하는 식으로 갈랐다(`d26a268`). 이 실패는 테스트를 처음 돌렸을 때 1건 빨갛게 뜬 뒤에야 알았다.

다섯째, Avro logical type 매핑. `occurredAt`은 `timestamp-millis` logical type인데, shared-protocol이 jsr310으로 생성돼서 setter가 `long`이 아니라 `Instant`를 받는다. `now.toEpochMilli()`를 넘겼다가 컴파일 에러가 났고 `now`를 그대로 넘기는 것으로 고쳤다(`0f47f0c`). 부하 테스트 환경에서도 비슷한 마찰이 있었다. bitnami/kafka가 arm64 manifest를 안 줘서 apache/kafka로 바꿨고, docker-compose의 JAR 파일명이 실제 산출물명과 안 맞아 컨테이너가 두 번 죽었다.

요약하면, 도메인 로직(ledger 합 = 0, 잔고 산술)은 C/C++ 경험으로 명확하게 짤 수 있었다. 시간은 Spring/JPA/Kafka/Avro 생태계의 관례를 처음 익히는 데 들었다. 트랜잭션 경계가 어떻게 전파되는지, 이벤트 리스너가 언제 fire하는지, ORM이 어떤 타입을 못 받는지 같은 "프레임워크가 암묵적으로 강제하는 규칙"이 학습 비용의 대부분이었다.

**(Phase 5 통합) LOST 정산의 빠진 wallet leg.** 전체 스택 e2e를 돌리고서야 드러난 결함이다. 베팅 접수 시 스테이크는 `BET_DEBIT`로 available→locked에 묶이고, 이긴 베팅은 `BET_REFUND`(locked→available) + `BET_PAYOUT`(house→user), push/void는 `BET_REFUND`로 풀린다. 그런데 **진 베팅을 처리하는 leg가 없어** 묶인 스테이크가 user의 locked에 영영 남았다 — house는 수금 못 하고 user 잔고도 정리되지 않는 상태였다. 흥미로운 건 도메인에 `Account.forfeitLocked` mutator와 `HOUSE` 시스템 계좌가 **이미 있었다**는 점이다. 설계 시점에 자리는 만들어 뒀는데 어떤 연산도 그 자리를 부르지 않았다. 단위 테스트는 각 연산을 따로 검증하므로 "정산 4결과 중 하나가 통째로 비어 있다"를 잡지 못했고, 전체 스택에서 LOST 베팅을 실제로 정산해 봐야 드러났다. 고친 모양은 작다 — `BET_FORFEIT` reason 하나, V3 CHECK 제약 확장, `runIdempotent`/`writePair` 위에 leg 매핑 하나(`95b8a60`, [020](../commits/020.md)). 교훈은, "primitive를 미리 만들어 두는 것"과 "그 primitive가 실제 흐름에 연결됐는지 검증하는 것"은 별개이고 후자는 통합 테스트의 몫이라는 점이다.

## 다시 한다면

영속성 경계를 코드 작성 전에 종이에 먼저 그린다. `EmbeddedMoney` wrapper가 필요하다는 사실을 `Account` 작성 도중에 알았는데, 도메인 모델과 JPA 매핑을 분리해서 먼저 스케치했다면 wrapper의 존재를 설계 시점에 넣었을 것이다.

트랜잭션 경계도 마찬가지다. "어디서 커밋하고, 실패 시 catch가 어느 트랜잭션에서 도는가"를 먼저 그렸다면 `TransactionTemplate` 선택을 시행착오 없이 했을 것이다. 선언적 `@Transactional`과 프로그래밍 방식 경계 중 무엇을 쓸지는 예외 복구 흐름을 그려야만 결정된다는 것을 이번에 배웠다.

shared-protocol의 이벤트 스키마를 wallet 흐름과 같이 봤어야 했다. `WalletCredited`의 reason enum은 `PAYOUT / VOID / REFUND`인데, deposit과 withdraw에 맞는 값이 없다. 그래서 V1에서 deposit/withdraw 이벤트를 publish하지 않기로 했다(`0f47f0c`). 이건 wallet만의 결정이 아니라 shared-protocol 스키마 설계 시점에 wallet의 자금 흐름 전체를 같이 놓고 봤어야 하는 cross-repo 합의 지점이다. 다음 leaf(risk-service 등)에서는 이벤트 스키마를 확정하기 전에 그 서비스의 전체 출력 이벤트 목록을 먼저 적는다.

부하 테스트 환경은 처음부터 arm64 호환 이미지로 docker-compose를 짠다. dev host가 Apple Silicon이라는 사실은 고정인데 이미지를 고를 때 그것을 의식하지 않아서 두 번 헛돌았다.

## 남은 한계

deposit과 withdraw는 Kafka 이벤트를 publish하지 않는다. 베팅 saga가 보지 않는 운영성 연산이고 shared-protocol의 credit reason enum에 맞는 값이 없기 때문이다. 운영에서 입출금 이벤트 구독이 필요해지면 새 이벤트 타입을 shared-protocol에 추가해야 한다. 이는 ADR-0012가 V1에서 닫은 범위(보너스, cash out 등)와 같은 결의 "지금은 필요 없어서 닫은" 결정이다.

`forfeit`도 같은 결로 Kafka 이벤트를 발행하지 않는다. settlement이 LOST 베팅마다 HTTP로 직접 구동하고 동기 응답을 받으므로 이벤트가 불필요하다. 또한 wallet은 forfeit가 정말 LOST인지 스스로 판단하지 않는다 — settlement의 결정을 신뢰해 locked→house를 기계적으로 수행하는 연산이고, 정산 결과 판정은 settlement의 책임 경계다. 운영에서 forfeit 이벤트(예: house 수익 집계)가 필요해지면 입출금 이벤트와 동형으로 shared-protocol에 새 이벤트를 더해야 한다.

`DailyReconciliationJob`은 account 테이블을 통째로 순회한다. 행이 많아지면 read 트랜잭션이 길게 잡히므로 `findAll(Pageable)` 기반 페이지네이션이 필요하지만, V1에서는 가상의 규모를 위한 복잡도를 미루고 단순하게 두었다.

계좌는 단일 통화다. KRW와 USD를 둘 다 지원하지만 한 계좌는 한 통화만 들고, cross-currency 환전 경로는 없다. 환율은 mock이다.

보너스 계좌는 V2 후보로 미뤘고, 결제 PG는 mock counterparty(`EXTERNAL_PAYMENT` 시스템 계좌)로만 모델링했다. Schema Registry 없이 Avro 바이트를 직접 주고받으므로(ADR-0014) 스키마 진화 검증은 shared-protocol 클래스 버전에 의존한다.

부하 목표인 5000 RPS는 dev host에서 검증하지 못했다. 1000 RPS에서 Docker Desktop의 CPU 풀이 포화되며 p95가 100ms대로 오른다. 에러율은 그 지점에서도 0이고 설계(per-user row lock + Redis fast path + outbox)에 구조적 한계는 보이지 않으므로, production급 하드웨어에서의 측정은 orchestration repo의 e2e 하니스가 준비될 때로 미뤘다.
