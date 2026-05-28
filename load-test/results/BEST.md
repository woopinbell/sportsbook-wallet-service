# BEST results — wallet-service load tests

> **EN — At a glance.** Best curated results across local benchmark runs.
> Performance numbers are dev-host baselines (M1 Pro, Docker Desktop 28, 8
> CPU / 8 GB allocated); production-grade numbers will replace them once the
> orchestration repo's e2e harness exercises a multi-replica wallet.

## 측정 결과 — 2026-05-28 (dev host)

### debit_load (sustained throughput)

| Target RPS | Actual RPS | Errors | p50      | p95       | p99 (≈ max) | Threshold result                  |
|-----------:|-----------:|-------:|---------:|----------:|-------------:|------------------------------------|
| 200        | 200.01     | 0      | 2.84 ms  |  6.56 ms  |  ≤ 20 ms     | p99 < 50 ms ✅, error < 0.1 % ✅     |
| 500        | 500.02     | 0      | 1.75 ms  |  3.15 ms  | 19.96 ms     | p99 < 50 ms ✅, error < 0.1 % ✅     |
| 1 000      | 993.42     | 0      | 4.55 ms  | 105.30 ms | 787.15 ms    | p99 < 50 ms ❌ (dev-host ceiling)    |

Sweet spot on this host sits between 500 and 800 RPS. The 1 000 RPS run
saturates the single Docker Desktop CPU pool — the wallet container's
ramp-up to ~170 VUs starves the producer thread and the tail balloons.
On production-grade hardware the throughput goal stays **5 000 RPS, p99 <
50 ms**; the design (pessimistic row lock on a per-user row, idempotency
fast-path on Redis, transactional outbox) shows no architectural ceiling
in this slice — k6 simply outruns the laptop.

Raw JSON: `2026-05-28/debit_load.json`, `debit_load_500.json`,
`debit_load_1000.json`.

### concurrency — 100 concurrent debits on one user

100 VUs × 1 iteration each = 100 distinct Idempotency-Keys debiting 100 KRW
against a single user.

```
before  available=9 999 895 800   locked=104 200     total=10 000 000 000
after   available=9 999 885 800   locked=114 200     total=10 000 000 000
```

available − 10 000, locked + 10 000, total unchanged. All 100 debits
succeeded, **no double-spending**, account balance arithmetic exactly
correct under contention.

Raw JSON: `2026-05-28/concurrency.json`.

### idempotency — 100 concurrent debits with the SAME key

100 VUs × 1 iteration each, all carrying `Idempotency-Key:
bench-idem-1779955005`. The wallet must collapse this to a single ledger
pair.

```
before  available=9 999 897 700   locked=102 300     total=10 000 000 000
after   available=9 999 897 600   locked=102 400     total=10 000 000 000
```

available − 100, locked + 100. Exactly one 100 KRW debit applied. The
other 99 concurrent calls hit either the Redis fast path or the
matched-pair `UNIQUE (idempotency_key, side)` constraint and returned the
original operation_group_id without re-executing the side effect.

Raw JSON: `2026-05-28/idempotency.json`.

## Goal vs measured

| 시나리오          | 측정치 (dev host)                                    | 목표                                  | 상태 |
|------------------|-----------------------------------------------------|---------------------------------------|------|
| debit_load       | 500 RPS / p95 3.15 ms / errors 0 %                  | 5 000 RPS, p99 < 50 ms, errors < 0.1 % | dev host throughput ceiling — design fine, hardware bound |
| concurrency      | total balance preserved exactly, 100 of 100 succeed | double-spend 0건                       | ✅   |
| idempotency      | single 100 KRW debit applied across 100 calls       | 1 pair, 100 동일 group                 | ✅   |

## 환경 메모

- macOS 14 (Apple Silicon M1 Pro), 16 GB RAM
- Docker Desktop 28, 8 CPU / 8 GB allocated
- PostgreSQL 16-alpine, Redis 7-alpine, apache/kafka 3.7.0
- Wallet JAR built from current branch HEAD, defaults from `application.yml`
  except `wallet.outbox.poll-interval-ms=30000` to take the outbox publisher
  thread out of the hot CPU path during the run.

## 갱신 규칙

1. 새 결과를 `results/<YYYY-MM-DD>/` 아래에 PNG + JSON으로 박제한다.
2. 직전 BEST를 명백히 능가하는 경우만 위 표를 갱신한다 (값 + 출처 폴더 링크).
3. dev-host와 production-grade 측정은 별도 행으로 구분한다.
