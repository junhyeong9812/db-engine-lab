# Stage 08-03 — Crash Simulation Tests (X6 보강)

> **Status**: tests only
> 깨지는 가정: 89 PASSED지만 단위 기능 통과 중심. 실패 주입 부족.

## 도입
- `CrashSimulationTest`:
  - COMMIT 안 적힌 tx → recovery 미반영
  - WAL partial trailing bytes → EOF 안전 처리
  - randomized commit/abort 30개 → recovery 결과가 commit set과 일치

## invariant
- crash 시뮬 모든 시나리오에서 Recovery 결과 = 명시적으로 commit한 tx 집합.
