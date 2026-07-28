## Striping - 하나의 ledger를 여러 Bookie에 담자.
Kafka의 한계
```text
Partition 0 의 리더 = Broker 1

  Producer ─────> Broker 1 ─── [디스크 1개]
                                    ↑
                        이 파티션의 처리량 상한 = 이 디스크 한 대의 성능
```
Broker 2,3에 복사본이 있긴 하지만 그건 똑같은 데이터 사본이다. 쓰기를 나눠 갖는 게 아니라 각자 전량을 다 쓴다.
"한 파티션 = 한 디스크"라는 게 Kafka의 근본 제약이다. 더 빠르게 하려면 파티션을 늘릴는 수 밖에 없다.

### Striping의 발상
한 줄기 데이터를 조각내어 여러 디스크에 나눠 쓰자. -> RAID 0과 같은 아이디어이다.
```text
메시지들:  m1  m2  m3  m4  m5  m6

Kafka 방식 (복제)          BookKeeper 방식 (striping)
  Broker1: m1 m2 m3 m4      Bookie1: m1    m4
  Broker2: m1 m2 m3 m4      Bookie2: m2    m5
  Broker3: m1 m2 m3 m4      Bookie3: m3    m6
  (셋 다 전량)               (돌아가며 나눠 담음)

  처리량 = 디스크 1개분       처리량 = 디스크 3개분
```
용어 정리 
- Ledger: append-only 로그 하나 (Kafka의 파티션에 해당)
- Bookie: 저장 노드 (Kafka의 브로커에 해당)
- Entry: 로그에 들어가는 레코드 하나

### 세 개의 숫자
BookKeeper는 세 가지 파라미터로 이 동작을 제어한다.
```text
E (Ensemble)  : 이 ledger에 참여하는 Bookie 총 인원
Qw (Write)    : entry 하나를 실제로 몇 개에 복제할지
Qa (Ack)      : 몇 개에게서 응답받으면 성공 처리할지

항상  E ≥ Qw ≥ Qa
```
예시: E=5,Qw=3,Qa=2
```text
Bookie:      B1  B2  B3  B4  B5

entry 1  →   ●   ●   ●              (B1,B2,B3)
entry 2  →       ●   ●   ●          (B2,B3,B4)
entry 3  →           ●   ●   ●      (B3,B4,B5)
entry 4  →   ●           ●   ●      (B4,B5,B1)
entry 5  →   ●   ●           ●      (B5,B1,B2)
             ↑ 한 칸씩 밀며 순환
```
읽어내는 방식:
- 각 entry는 3벌 존재 -> 2대까지 죽어도 안전(복제)
- 5대 전부가 일을 나눠 함 -> 쓰기 부하가 5대에 분산(striping)
- 2대만 응답하면 즉시 성공 -> 느린 한 대가 전체를 붙잡지 않는다.(straggler 회피)
Kafka의 "acks=all"은 ISR 전원을 기다리므로 가장 느린 팔로워가 전체 속도를 결정한다. Qa는 "빠른 다수만 기다린다."는 정족수(quorum) 방식이라 지연 시간의 꼬리가 짧다.

### 리더가 없다는 점
이게 구조적으로 큰 차이이다.
```text
Kafka:        Producer → [Leader] → Follower들이 fetch
              리더 죽음 → 선출 절차 → 그동안 해당 파티션 중단

BookKeeper:   Client가 Qw개 Bookie에게 직접 병렬 전송
              Bookie는 서로를 모름. 조율은 클라이언트가 함
              Bookie 죽음 → 그 자리만 다른 Bookie로 교체하고 계속
```
BookKeeper에서 순서 보장은 리더가 아니라 entry ID와 "하나의 ledger에는 오직 한 명의 writer만 존재한다"는 규칙으로 이뤄진다.
Bookie가 죽었을 때의 대응이 특히 가볍다.
```text
E=5 중 B3 사망
  → 그 시점 이후 entry부터 B6를 대신 투입 (ensemble change)
  → 이미 쓴 데이터는 그대로 두고, 백그라운드에서 복제본 수 복구
  → 서비스 중단 없음
```
kafka는 브로커를 늘리면 파티션 데이터를 통째로 물리적으로 옮겨야 하는데(수 TB 이동),BookKeeper는 새 데이터부터 새 Bookie를 쓰면 된다.

### Bookie 내부의 디스크 분리
striping 못지 않게 중요한 부분이다.
```text
      쓰기 요청
         │
    ┌────┴────┐
    ↓         ↓
[Journal]  [Memtable] ──(비동기)──> [Ledger 디스크]
 디스크                                  ↑
 순수 append만                       읽기는 여기서
 → 여기서 fsync하고 즉시 ack
```
- Journal디스크: 쓰기만 담당. 순수 순차 append. fsync 후 ack하므로 전원 차단에도 유실없다.
- Ledger 디스크: 읽기 담당. 백그라운드로 정리해서 사용한다.

효과는 읽기가 쓰기를 방해하지 않는다는 것이다.
Kafka에서는 컨슈머가 한참 뒤쳐지면 그 컨슈머의 랜덤 읽기가 디스크를 점유해서 쓰기 지연까지 같이 튄다.
BookKeeper는 물리적 디스크가 분리돼 있어 이 간섭이 없다.
또 Kafka는 페이지 캐시를 믿고 fsync를 생략(복제로 보완)하는 반면, BookKeeper는 저널에 fsync를 해도 순차 append 전용 디스크라 감당이 된다.
내구성 보장 수준이 더 강하다.

```text
                       Kafka                        BookKeeper
파티션당 처리량            디스크 1대                   디스크 E대
확장                    데이터 물리 이동 필요            새 데이터부터 새 노드 사용
지연 꼬리                가장 느린 replica에 좌우        Qa 정족수로 회피
읽기/쓰기 간섭            있다                         디스크 분리로 없음
내구성                  복제에 의존                    저널 fsync + 복제
복잡도                  브로커만                       Booker + Bookie + ZK
디스크 대수              적음                          Bookie당 2개 이상 필요
생태계                  압도적                        상대적으로 작음
```
BookKeeper가 기술적으로 더 정교하지만 Kafka가 여전히 지배적인 건, 대부분의 경우 "파티션을 늘리면 해결되는 문제"이고 운영 복잡도가 그 대가보다 크기 때문이다.
