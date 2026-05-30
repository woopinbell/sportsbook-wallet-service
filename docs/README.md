# `docs/` 학습 자료 안내

이 디렉터리는 `wallet-service` 저장소의 학습 자료를 모은다. `src/`와 `load-test/`가 실제 빌드와 검증에 쓰이는 코드라면, `docs/`는 그 코드가 어떤 결정으로 지금 모양에 도달했는지 설명하는 자료다. 실행이나 배포에는 사용되지 않는다.

## 구성

- `commits/`: `main`의 dev 커밋 하나하나를 번호 순으로 풀어쓴 문서다. 각 파일은 개요, 작업 순서, 작업 내역, 결과, 요약, 다음 작업, 핵심 확인, 기억/설명 Level을 같은 골격으로 담는다.
- `commits/README.md`: 커밋 문서의 목차와 작성 규칙, L3/L2 빠른 참조를 둔다. `commits/`를 처음 펼 때 가장 먼저 보는 자리다.
- `reflection/`: 끝낸 뒤의 회고(`retrospective.md`)와 요구 변화 시 깨질 위치·복구 비용 추정(`change-cost.md`)을 분리해 둔다.

`docs/notes/`는 두지 않는다. Phase 1 shared-protocol만 독립 토픽 reference를 가졌고, Phase 2부터는 학습 내용을 `commits/NNN.md` 본문과 그 `기억/설명 Level` 색인으로 흡수한다(2026-05-29 결정).

## 읽는 순서

1. `commits/README.md`를 먼저 읽어 어떤 번호를 어떤 책임으로 풀었는지, 어떤 규칙으로 본문이 작성됐는지 확인한다.
2. `commits/000.md`부터 번호 순서대로 읽는다. 골격 → 도메인 → 영속성 → service(멱등성) → outbox → REST → 정합성 → 부하 테스트 순서가 그대로 학습 동선이 된다.
3. 면접·복기처럼 깊이 다시 보고 싶은 자리는 `commits/README.md`의 `## L3 빠른 참조`로 한 번에 진입한다.

## 범위

이 저장소는 `main`의 dev 커밋만 문서 대상으로 삼는다. 공개 README 정리와 retrospective 산출물 커밋은 `commits/` 색인에서 제외한다. 본문이 가리키는 파일·클래스·메서드는 모두 `main` 시점의 코드와 일치한다.
