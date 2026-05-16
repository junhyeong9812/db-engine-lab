# handoff/ — 페이즈/단계 간 컨텍스트 인계 문서

> **목적**: 한 단계를 끝낸 뒤, 다음 단계 진입 시 **새 세션의 Claude 또는 codex가 한 문서만 읽으면 컨텍스트를 회복**할 수 있도록 한다.
> **부수 효과**:
> - 사용자가 며칠~주 후 복귀해도 자기 컨텍스트 회복 빠름.
> - codex 호출 시 첨부 자료로 사용 → 매번 컨텍스트 구성 부담 감소.

---

## 사용 시점

| 시점 | 생성/갱신 |
|------|----------|
| 단계 N 완료 직후 | `docs/handoff/stage-NN-handoff.md` 신규 생성 |
| Phase A 종료 시 | `docs/handoff/phase-a-summary.md` 신규 생성 |
| Phase B 종료 시 | `docs/handoff/phase-b-summary.md` 신규 생성 |
| 새 세션 시작 시 | 가장 최근 handoff 문서를 가장 먼저 읽음 |
| codex 호출 시 | handoff 문서 본문을 codex 입력에 첨부 |

---

## 표준 양식 — `stage-NN-handoff.md`

```markdown
# Handoff: Stage NN (<주제>) 완료 시점

> 작성일: YYYY-MM-DD
> 직전 handoff: stage-(NN-1)-handoff.md
> 다음 단계: NN+1 (<주제>)

## 0. 한 줄 요약
이 단계에서 무엇을 만들었고 무엇이 동작하는가.

## 1. 결정된 사항 (누적)
- D-XXX ~ D-YYY 요약 (decision-log 참조)

## 2. 만족하는 invariant (누적)
- I-1 (단계 1): ___
- I-2 (단계 2): ___
- ...
- I-N (단계 N): ___

## 3. 사용한 참조 출처
- SimpleDB: 파일 X (commit Y)
- BusTub (대조): 파일 X (commit Y)
- 참조 부재 영역: ___

## 4. 핵심 코드 위치
- 패키지: `com.dbenginelab.<package>`
- 핵심 클래스/파일 목록 + 한 줄 책임

## 5. 깨진 가설 / 갱신된 결정
- 단계 N에서 가설 X가 깨짐 → 가설 Y로 갱신
- decision-log D-XXX-rev 참조

## 6. 사용자가 막혔던 지점 (학습 메모)
- 단계 N의 ___ 개념 이해에 시간 ___ 소요
- 도움이 된 자료: ___

## 7. Spot check 결과
- 단계 N의 SimpleDB 원본 확인: ✅/❌ (불일치 시 D-XXX 참조)

## 8. 다음 단계가 받아야 할 입력
- 클래스/패키지 의존성
- 깨야 할 가정 (다음 단계의 동기)

## 9. 새 세션을 위한 권고
- 이 문서 + `sequence.md` + 직전 `stages/NN-stage.md` + 마지막 `impl/NN-MM.md` 읽기.
- codex 호출 시 본 문서 첨부.
```

---

## 표준 양식 — `phase-X-summary.md`

```markdown
# Phase X 종료 회고

## 1. 학습 목표 달성도
- 목표: ___
- 달성: ___
- 미달성/지연: ___

## 2. 단계별 회고 요약
| 단계 | 소요 시간 | 막힌 지점 | 핵심 학습 |

## 3. 깨진 가설들 (Phase 전체)
- 가설 → 갱신 이력

## 4. 다음 Phase 전 준비
- Phase Y 진입 전 정리할 것

## 5. 만족하는 invariant 최종 목록
- (다음 Phase가 받는 누적 invariant)

## 6. 외부 자료 평가
- SimpleDB 참조 유용성: ___
- BusTub 대조 유용성: ___
- 부재 영역의 대안 자료: ___
```

---

## 핵심 원칙

1. **handoff 문서는 1회 쓰고 끝** — 다음 단계 진입 후 변경 금지 (시간 순 snapshot).
2. **갱신은 새 handoff에서** — 단계 N+1 완료 시 N+1 handoff를 새로 작성하며 이전 정보 누적.
3. **handoff는 "이 시점에 보내는 메시지"** — 미래 자신/세션에게.
4. **분량 제한** — 한 handoff 1500자 이내. 길어지면 본문은 다른 문서에 두고 handoff는 링크.

---

## 단계 0 (현재 시점) handoff

문서 설계 완료 + Kotlin Gradle 골격 완료 시점의 컨텍스트는 `stage-00-initial.md` 참조.

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 |
