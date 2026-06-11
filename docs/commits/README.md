# `docs/commits/` 안내

## 목적

이 디렉터리는 `wallet-service`의 `main`에 쌓인 dev 커밋을 번호 단위로 풀어쓴 문서를 모은다. 코드 변경을 그대로 옮기는 것이 아니라, 그 변경이 왜 그 시점에 그 모양으로 들어왔는지를 설명한다. double-entry ledger와 멱등성, 분산 트랜잭션 같은 이 저장소의 결정과 그 결과를 우선한다.

## 문서 범위

문서 대상은 `main`의 dev 커밋 중 기능, 테스트, 빌드 흐름을 다룬 커밋이다. 공개 README를 다듬은 커밋(`57800a5 docs(readme): add performance section`)과 retrospective 산출물 커밋(`docs(notes)`, `docs(commits)`, `docs(practice)`)은 색인에서 제외한다. dev 커밋 22개 중 README 정리 1개를 빼고 21개를 `000.md`부터 `020.md`까지 번호 순으로 매핑했다. 마지막 `020`은 dev 단계가 아니라 Phase 5 전체 스택 통합에서 드러난 LOST 정산 leg를 채운 커밋이다. `021`부터는 phase 2(후속 윈도우)의 커밋이다 — 목차 아래 phase 경계 참조.

## 읽는 방법

처음 보는 독자는 `000.md`부터 번호 순서대로 읽는다. 각 문서는 `## 개요` → `## 작업 순서` → `## 작업 내역` → `## 결과` → `## 요약` → `## 다음 작업` → `## 핵심 확인` → `## 기억/설명 Level` 순서이고, 실체 있는 커밋 답지(`001`–`020`)는 끝에 `## diff 반영 점검`(변경 파일 누락 검산)을 더한다. `## 작업 내역`의 코드 스니펫은 그 커밋이 도입한 핵심 시그니처나 흐름만 담는다. 전체 파일을 옮기지 않는다.

면접·복기처럼 색인이 먼저 필요한 자리는 아래 `## L3 빠른 참조` / `## L2 빠른 참조`로 진입한다.

## 목차

| 번호 | 해시 | 주제 |
|------|------|------|
| [000](000.md) | `7f54434` | chore(project): wallet-service 골격 초기화 |
| [001](001.md) | `34c59e6` | feat(domain): Account 집합체 (available + locked) |
| [002](002.md) | `62d6247` | feat(domain): LedgerEntry 분개장 (double-entry) |
| [003](003.md) | `ec94e6d` | build(flyway): V1 스키마 (account + ledger_entry) |
| [004](004.md) | `fe4d81f` | test(domain): 도메인 단위 테스트 |
| [005](005.md) | `6348505` | feat(repository): AccountRepository 비관적 락 |
| [006](006.md) | `68e3f49` | feat(repository): LedgerEntryRepository 읽기 |
| [007](007.md) | `92c6564` | feat(service): WalletService 멱등 전송 primitive |
| [008](008.md) | `e8ac469` | test(service): Testcontainers 통합 테스트 |
| [009](009.md) | `ff3bff4` | build(flyway): V2 스키마 (outbox_event) |
| [010](010.md) | `2d788d2` | feat(outbox): OutboxEvent entity + publisher |
| [011](011.md) | `0f47f0c` | feat(events): 결과 이벤트 발행 배선 |
| [012](012.md) | `6d5c05c` | test(outbox): 원자성 + end-to-end 발행 |
| [013](013.md) | `497c18e` | feat(api): /internal/v1/wallet REST 표면 |
| [014](014.md) | `445e92c` | feat(api): RFC 7807 ProblemDetail 매핑 |
| [015](015.md) | `d81b9a4` | test(api): @WebMvcTest |
| [016](016.md) | `809292f` | feat(integrity): after-commit InvariantChecker |
| [017](017.md) | `b1ac969` | feat(scheduler): DailyReconciliationJob |
| [018](018.md) | `d26a268` | test(integrity): drift 탐지 |
| [019](019.md) | `5915efb` | test(load): k6 baseline |
| [020](020.md) | `95b8a60` | feat(wallet): forfeit — 패배 스테이크 locked→house (V3) · Phase 5 통합 |
| [021](021.md) | `28e681f` | build(maven): maven wrapper 체크인 — 균일 빌드 · phase 2 |

> **phase 경계**: ~020 + retrospective 메타 = phase 1. **[021]부터 phase 2**(후속 윈도우,
> 2026-06-11 시작, 시작 커밋 `28e681f`) — 경계 규정은 commit-policy.md §날짜·배치(phase 단위).

## 작성 규칙

- 1 dev 커밋 = 1 문서. 한 문서가 여러 커밋을 묶지 않는다.
- 코드 스니펫에는 그 커밋이 도입한 시그니처나 핵심 흐름만 둔다. 전체 파일을 옮기지 않는다.
- 버전 관리·브랜치 조작은 문서화 대상이 아니다.
- README 정리와 retrospective 산출물 커밋은 색인에서 제외한다.

## Level 작성 규칙

각 문서 마지막 `## 기억/설명 Level`은 본문 요약이 아니라 면접 복습용 색인이다.

- **L3 - 백지 설명 대상**: 문서를 닫고도 구조·흐름·결정·핵심 제약을 설명할 수 있어야 한다. 골격을 정하거나 다른 커밋에서 반복 참조되는 사고 단위가 들어간다.
- **L2 - 코드/문서 기반 설명 대상**: 소스나 문서를 보며 구현 흐름·책임 연결·예외 처리를 설명할 수 있어야 한다.
- **L1 - 읽고 파악하면 충분한 대상**: 코드를 읽으면 의도와 역할을 파악할 수 있다. 설정·디렉터리 의미·도구 사용법 같은 항목이다.

`## L3 빠른 참조`에는 L3 항목만, `## L2 빠른 참조`에는 L2 항목만 등록한다. L1만 가진 커밋은 본인 문서의 Level 섹션에만 둔다.

## L3 빠른 참조

### 도메인 모델

- [001](001.md): available / locked 2-bucket으로 베팅 노출을 계좌 밖으로 옮기지 않고 분리해 운영 액션과 reconciliation 추적을 가능하게 하는 모델 결정
- [002](002.md): double-entry 차변/대변 규약과 시스템 합 = 0 invariant, 그리고 그것이 이후 두 정합성 장치의 검증 대상이 되는 구조
- [002](002.md): 멱등성을 `UNIQUE(idempotency_key, side)`로 강제하면서 matched-pair 두 행을 허용하는 제약 설계
- [020](020.md): 정산 4결과(win=REFUND+PAYOUT / push·void=REFUND / lose=FORFEIT)를 wallet leg로 매핑하는 전체 그림 — 진 스테이크를 locked→house로 capture하는 `BET_FORFEIT`가 빠져 있던 LOST leg를 채운 것, 그리고 도메인 primitive(`forfeitLocked` + HOUSE 계좌)는 있었으나 미배선이었다는 점

### 동시성과 멱등성

- [005](005.md): `@Lock(PESSIMISTIC_WRITE)`가 사용자 계좌 행을 잡아 동시 debit을 직렬화하는 메커니즘과 락 해제가 트랜잭션 커밋에 묶이는 점
- [007](007.md): 멱등성 3층(Redis fast path / 비관적 락 write path / DB 제약 race-loser 복구)과 각 층의 역할 분담
- [007](007.md): `@Transactional` 대신 `TransactionTemplate`을 택한 이유(rollback-only 트랜잭션에서 추가 read 불가)
- [007](007.md): 네 연산을 (목적지, 출발지) leg 매핑으로 단일 primitive 위에 올리는 구조
- [008](008.md): 100건 동시 debit이 단일 분개 쌍으로 수렴하는 것을 실제 PostgreSQL 위에서 증명하는 race 구조

### 메시징

- [009](009.md): transactional outbox가 DB 커밋과 메시지 발행의 원자성을 보장하는 방식(같은 트랜잭션에 이벤트를 쓰고 별도 publisher가 흘림)
- [010](010.md): outbox publisher의 retry-until-acked 루프가 at-least-once를 만드는 메커니즘과 idempotent producer의 중복/순서 방지
- [011](011.md): 성공 이벤트를 원장과 같은 트랜잭션에, 실패 이벤트를 별도 트랜잭션에 쓰는 이유와 트레이드오프

### 정합성

- [016](016.md): after-commit invariant 검증이 트랜잭션 시점 방어와 역할을 나누는 구조, 위반을 되돌릴 수 없어 알람으로 대응하는 운영 모델
- [017](017.md): per-operation 검사와 일일 배치의 이중 정합성, 스냅샷 + 증분 모델에서 배치가 스냅샷-원장 일치를 검증해 닫는 고리

### 검증

- [019](019.md): 부하 테스트가 통합 테스트의 정합성 증명을 더 큰 규모로 재현하는 구조와 dev-host 처리량 한계가 hardware bound라는 판단 근거

## L2 빠른 참조

- [001](001.md): 의도 이름 mutator 다섯 개와 자금 흐름의 1:1 대응, `EmbeddedMoney` wrapper가 필요한 이유
- [002](002.md): `pair()` 팩토리 흐름과 `SystemAccountIds`가 외부 상태 없이 합 = 0을 성립시키는 역할
- [003](003.md): CHECK 제약 defence-in-depth, ledger_entry에 FK를 두지 않는 이유, 두 인덱스의 읽기 경로
- [004](004.md): mutator별 `@Nested` 그룹 구성과 `pair()` invariant 고정
- [005](005.md): 락 없는 조회와 락 거는 조회를 메서드로 분리하는 설계
- [006](006.md): 세 파생 쿼리가 받치는 경로(재시도 복구 / Redis probe / reconciliation 스캔)
- [007](007.md): `runIdempotent` 분기 흐름, `openAccount` PK 경합 처리, `Clock` 주입
- [008](008.md): `@Testcontainers` + `@DynamicPropertySource` 배선과 빈 datasource 기본값의 의도
- [009](009.md): partition_key에 userId를 담아 사용자별 파티션 순서를 보존하는 설계, partial index가 미발행 행만 인덱싱해 작게 유지되는 이유
- [010](010.md): `@Scheduled` + `Pageable` 배치 구성, `markPublished` 멱등성
- [011](011.md): `WalletEventFactory` 토픽/스키마/파티션 키 규약과 deposit/withdraw 미발행 근거
- [012](012.md): 자동 발행 tick park 이유와 다섯 테스트 흐름
- [013](013.md): endpoint 6개 책임과 DTO를 shared-protocol 타입으로 통일하는 방식
- [014](014.md): status/slug 매핑 표와 500 본문을 좁게 두는 보안 결정
- [015](015.md): `@WebMvcTest` + `@MockBean` 슬라이스 구조
- [016](016.md): `OperationCommitted` publish 지점과 AFTER_COMMIT phase의 롤백 거르기
- [017](017.md): 시스템 net + 계좌별 bucket 대조 흐름과 Micrometer 게이지 공유
- [018](018.md): AFTER_COMMIT 배선 검증과 검증 로직 검증을 나눈 이유
- [019](019.md): constant-arrival-rate + k6 threshold CI 게이트 구성
- [020](020.md): forfeit가 [007](007.md)의 `runIdempotent` / `writePair`를 재사용하고 business-rejection 분기 없이 leg·reason만 더하는 구조, V3 CHECK 제약 확장, `settle:forfeit:{betId}` 멱등 키
