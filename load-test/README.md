# wallet-service load tests

> **EN — At a glance.** Three k6 scenarios that exercise the wallet's hot
> path (sustained debit storm) and its two Level-3 정합성 invariants (no
> double-spend under contention, same-key retries collapse to one ledger
> pair). docker-compose brings up PostgreSQL 16 + Redis 7 + Kafka 3.7 + the
> wallet-service JAR on isolated ports (55432 / 56379 / 59092 / 58081). The
> service target is **5 000 RPS debit, p99 < 50 ms, double-spending 0**;
> baseline results on the development host are recorded in `results/BEST.md`.

## 실행

### 1. JAR 빌드 + 컨테이너 기동

```sh
mvn -DskipTests package
docker compose -f load-test/docker-compose.yml up -d
```

PostgreSQL + Redis + Kafka + wallet-service가 격리된 포트로 올라온다 (호스트의 dev DB와 충돌하지 않도록 의도적으로 비표준 포트). wallet-service의 health endpoint가 ready 상태가 될 때까지 대기:

```sh
until curl -fsS http://localhost:58081/actuator/health/readiness; do sleep 2; done
```

### 2. 사용자 시드

`seed.js`는 N개의 user account를 만들고 각각 큰 금액을 입금한 뒤 stdout으로 UUID를 출력한다.

```sh
mkdir -p load-test/results/raw
k6 run --quiet --vus 10 --iterations 200 \
       -e BASE_URL=http://localhost:58081 \
       load-test/scenarios/seed.js 2>/dev/null \
   | grep -E '^[0-9a-f-]{36}$' > load-test/scenarios/users.txt
```

(k6는 `console.log`를 stderr로 보내므로 `2>&1` 또는 `--console-output` 옵션이 필요할 수 있다.)

### 3. 시나리오 실행

```sh
# Throughput — sustained debit storm
k6 run -e BASE_URL=http://localhost:58081 \
       -e RATE=500 -e DURATION=60s \
       --summary-export=load-test/results/raw/debit_load.json \
       load-test/scenarios/debit_load.js

# 정합성 — 100 concurrent debits on the same user (distinct keys)
USER_ID=$(head -n 1 load-test/scenarios/users.txt)
k6 run -e BASE_URL=http://localhost:58081 -e USER_ID=$USER_ID \
       --summary-export=load-test/results/raw/concurrency.json \
       load-test/scenarios/concurrency.js

# 정합성 — 100 concurrent debits with the SAME Idempotency-Key
USER_ID=$(sed -n '2p' load-test/scenarios/users.txt)
k6 run -e BASE_URL=http://localhost:58081 -e USER_ID=$USER_ID \
       --summary-export=load-test/results/raw/idempotency.json \
       load-test/scenarios/idempotency.js
```

### 4. 정리

```sh
docker compose -f load-test/docker-compose.yml down -v
```

## 시나리오 한눈에

| 파일                    | 목적                                                              | 목표                                       |
|------------------------|------------------------------------------------------------------|--------------------------------------------|
| `scenarios/seed.js`     | 부하 테스트용 user 계좌 + 잔고 시드                                | (사전 작업)                                 |
| `scenarios/debit_load.js` | constant-arrival-rate로 debit storm, p99 / 에러율 측정             | 5 000 RPS, p99 < 50 ms, 에러율 < 0.1 %      |
| `scenarios/concurrency.js`| 단일 user에 100 동시 debit (distinct keys), 잔고 보존 확인         | double-spending 0, 잔고 일관성              |
| `scenarios/idempotency.js`| 동일 Idempotency-Key로 100 동시 debit                              | 단일 ledger pair, 응답 100건 동일 group     |

`debit_load.js`는 k6 threshold에 목표 p99 / 에러율을 박제해 두었기 때문에 미달 시 exit code 가 0이 아니다 — CI 게이트로 그대로 사용 가능.

## 결과 저장

- `results/raw/*.json` — k6 `--summary-export` 출력 (git 비추적, `.gitignore` 참조).
- `results/BEST.md` — 큐레이션한 최고 성능 기록. dev 호스트의 베이스라인 + 향후 production-grade 환경에서의 측정치 누적.
- `results/<YYYY-MM-DD>/` — 시각화 결과(PNG)와 환경 메타데이터를 함께 보관. 명백히 더 좋은 숫자가 나오면 BEST.md를 갱신.

## 한계 / V1 미구현

- **wallet 단일 인스턴스**: docker-compose는 service replica 1개로 띄운다. Phase 5(orchestration) e2e에서 multi-replica + load balancer로 확장 예정.
- **Schema Registry 없음** (ADR-0014): Kafka 메시지는 Avro binary, schema는 shared-protocol 클래스에 고정. 정합성에는 영향 없지만 production-grade에서는 Apicurio 도입 필요.
- **dev 호스트 측정의 변동성**: laptop의 CPU 경합 / Docker Desktop 오버헤드 때문에 동일 시나리오라도 ±30% 편차가 보인다. BEST.md는 5회 이상 반복 후 중간값 기준.
- **fault injection 미포함**: Toxiproxy 등은 Phase 5의 `orchestration/chaos/`에서 다룬다.
