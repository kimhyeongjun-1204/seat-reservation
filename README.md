# 공연 좌석 예약 시스템

짧은 시간에 같은 공연으로 동시 요청이 몰리고, 여러 서버가 하나의 DB를 공유하는 상황을 가정한 좌석 조회·선점·선점 취소·결제 이벤트 처리 서버입니다.
정합성은 애플리케이션 메모리가 아니라 DB(유니크·CHECK 제약, 조건부 UPDATE, 트랜잭션, 행 락)로만 보장합니다.

- 기술 스택: Java 21, Spring Boot 4.0.8, Spring Data JPA(Hibernate 7), MariaDB 11.4, Flyway, Gradle
- 테스트: JUnit 5, Testcontainers 2.x(MariaDB 11.4)
- 설계 결정과 근거: [DECISIONS.md](DECISIONS.md) / 구현하지 못한 것과 타협: [NOT-DONE.md](NOT-DONE.md) / AI 사용 기록: [AI-USAGE.md](AI-USAGE.md)

## 실행 방법

필요한 것: JDK 21(Gradle toolchain), Docker Desktop. 아래 명령은 Windows 기준입니다.

```bat
:: 1. 로컬 MariaDB 실행 (localhost:3307, DB·계정 seat / seat)
docker compose up -d

:: 2. 앱 실행 (http://localhost:8080)
gradlew.bat bootRun
```

앱이 시작될 때 Flyway가 스키마(V1)와 초기 데이터(V2)를 적용합니다. 만료 시간은 `application.yaml`의 `seat.hold.ttl`(기본 `5m`)로 바꿀 수 있습니다.

## 초기 데이터

Flyway 마이그레이션 `src/main/resources/db/migration/V2__seed_data.sql`로 준비합니다. 별도의 좌석 생성 기능은 없습니다.

- 공연 1개(id 1) × 좌석 1,000석
- 좌석 번호 A-1부터 J-100까지, 좌석 id 1부터 1000까지 (A열 id 1-100을 인기 구간으로 가정)
- MariaDB Sequence 엔진의 `seq_0_to_999` 가상 테이블로 1,000행을 한 문장에 만든다

데이터를 처음 상태로 되돌리려면 볼륨까지 지우고 다시 실행합니다.

```bat
docker compose down -v
docker compose up -d
```

테스트는 이 초기 데이터에 의존하지 않고, 테스트마다 자기 공연과 좌석을 만듭니다.

## API

| 기능 | 요청 | 응답 |
|---|---|---|
| 좌석 조회 | `GET /performances/{id}/seats` | 200 `[{seatId, seatNo, status}]` (만료된 선점은 AVAILABLE로 계산) / 404 |
| 좌석 선점 | `POST /holds` + 헤더 `X-User-Id`, 본문 `{performanceId, seatIds}` | 201 `{holdId, performanceId, seatIds, expiresAt}` / 400 / 404 / 409 |
| 선점 취소 | `DELETE /holds/{holdId}` + 헤더 `X-User-Id` | 204 / 404 |
| 결제 이벤트 수신 | `POST /payment-events`, 본문 `{eventId, paymentId, holdId, status, occurredAt}` | 200 `{eventId, result, paymentStatus}` / 400 / 404 / 409 / 503 |

- 한 번에 선점할 수 있는 좌석은 1-10석이고, 같은 좌석을 중복해서 보내면 400입니다.
- 결제 이벤트의 `status`는 `approved` 또는 `cancelled`, `result`는 `APPLIED`·`IGNORED`·`DUPLICATE`입니다.
- 오류는 ProblemDetail(RFC 9457) 형식에 `code` 필드를 더해 돌려줍니다 (`INVALID_REQUEST`, `PERFORMANCE_NOT_FOUND`, `HOLD_NOT_FOUND`, `SEAT_UNAVAILABLE`, `LOCK_CONFLICT`, `PAYMENT_CONFLICT`).
- 락 대기 초과(1205)·데드락(1213)은 서버에서 재시도하지 않습니다. 선점·취소에서는 409 `LOCK_CONFLICT`, 결제 이벤트에서는 이벤트 기록까지 롤백되므로 결제 제공자의 재전송을 받기 위해 503 `LOCK_CONFLICT`로 응답합니다. 1205는 테스트로 확인했고, 1213은 측정 중 한 번도 발생하지 않아 응답을 확인하지 못했습니다.
- 시각은 ISO-8601 UTC 문자열입니다.
- 경로는 과제 예시를 그대로 따랐습니다. 사용자 식별자는 인증 없이 `X-User-Id` 헤더 값을 그대로 씁니다(과제 조건).

예시 (로컬에서 실제로 실행해 확인한 요청):

```bash
curl -X POST localhost:8080/holds -H "X-User-Id: user-1" -H "Content-Type: application/json" -d "{\"performanceId\":1,\"seatIds\":[999,1000]}"
curl -X POST localhost:8080/payment-events -H "Content-Type: application/json" -d "{\"eventId\":\"evt-1\",\"paymentId\":\"pay-1\",\"holdId\":\"<위 응답의 holdId>\",\"status\":\"approved\",\"occurredAt\":\"2026-10-04T06:10:00Z\"}"
```

## 테스트 실행

Docker Desktop이 실행 중이어야 합니다. Testcontainers가 MariaDB 11.4 컨테이너를 띄웁니다.

```bat
gradlew.bat cleanTest test
```

`cleanTest`를 함께 주는 이유: 코드가 바뀌지 않았으면 Gradle이 `test`를 UP-TO-DATE로 건너뛰기 때문입니다.

| 테스트 | 확인하는 것 |
|---|---|
| `SchemaMigrationTest` | CHECK 제약(좌석 상태 조합), payment 1:1 연결, 초기 데이터 1,000석, INSERT IGNORE와 CHECK 위반의 관계 |
| `HoldApiTest` | 좌석 조회·선점·취소 API, 400·404·409, 부분 실패 롤백, 지연 만료 |
| `HoldConcurrencyTest` | 같은 좌석 동시 선점, 순서가 엇갈린 다중 좌석 요청, 겹치는 다중 좌석 요청 |
| `HoldTimeZoneTest` | 세션 시간대가 다른 연결(+09:00 / +00:00)에서도 같은 만료 판정 |
| `HoldQueryPlanTest` | 선점 UPDATE의 실행 계획 출력 (단언 없음) |
| `PaymentEventApiTest` | 입력 검증, 전이표, 순서 역전, 만료·취소 후 승인, 결제·선점 1:1 연결 |
| `PaymentEventConcurrencyTest` | 같은 eventId 동시 재전송, 서로 다른 결제 동시 승인, 같은 결제 승인·취소 동시, 승인과 사용자 취소 경합, 만료 경계에서 승인과 재선점 경합 |
| `PaymentEventTimeZoneTest` | +09:00 세션에서 저장한 occurredAt이 같은 절대 시각인지 |
| `LockTimeoutResponseTest` | 실제 락 대기 초과(1205)가 선점 409, 결제 503 `LOCK_CONFLICT`로 바뀌는지 |

### 테스트에서 측정한 값

조건: 로컬 PC(Windows), Docker Desktop, Testcontainers MariaDB 11.4.13, `gradlew.bat cleanTest test` 1회 실행(10/4 14:13, 66개 통과, 67초). 동시성 테스트는 서비스 메서드를 스레드에서 직접 호출하며 HTTP를 거치지 않습니다.

| 시나리오 | 결과 |
|---|---|
| 같은 좌석에 50건 동시 선점 | 성공 1, SEAT_UNAVAILABLE 49 |
| 좌석 {a,b}와 {b,a} 순서로 동시 선점, 100회 반복 | 매회 1건만 성공, 데드락·락 대기 초과 0건 |
| 10석에 1-4석짜리 요청 100건 동시 | 성공 5, 실패 95, 부분 선점 0 (성공 수는 요청 배치와 타이밍에 따라 실행마다 5-8건으로 달라짐) |
| 같은 eventId 20건 동시 | APPLIED 1, DUPLICATE 19, 이벤트 기록 1행 |
| 서로 다른 선점 50개의 첫 승인 이벤트 동시 | 50건 모두 APPROVED, 좌석 100석 SOLD, 락 충돌 0건 |
| 같은 결제의 approved·cancelled 동시, 30회 | 409 0건, 모두 최종 CANCELLED, 좌석 AVAILABLE |
| 승인과 사용자 취소 동시, 30회 | 승인 우선 6, 취소 우선 24, 매회 한쪽만 성공, 락 충돌 0건 |
| 만료 300ms 전후에 승인과 다른 사용자의 재선점 경합, 30회 | SOLD·APPROVED 15회, REFUND_REQUIRED·재선점 성공 15회, 둘이 동시에 성립 0회 |

선점 UPDATE의 실행 계획(EXPLAIN)은 `type=range, key=PRIMARY, rows=4`(seat 2,211행 중 요청 4석)였습니다.

## 재시작 후 판매 상태 유지 (수동 확인)

자동화 테스트는 없고, 로컬에서 다음 순서로 한 번 확인했습니다(10/4 15:08-15:09).

1. `docker compose up -d`, `gradlew.bat bootRun`
2. 좌석 999·1000 선점 → 승인 이벤트 → 조회 결과 두 좌석 SOLD
3. 앱 프로세스를 강제 종료(`Stop-Process -Force`) 후 다시 `gradlew.bat bootRun`
4. 조회 결과 두 좌석이 SOLD 그대로, 다른 사용자의 999 선점은 409 `SEAT_UNAVAILABLE`, 같은 승인 이벤트 재전송은 `DUPLICATE / APPROVED`

보장 방식은 DECISIONS.md D-11에 적었습니다.

## 운영자용 조회: 환불이 필요한 결제

만료되거나 취소된 선점에 승인 이벤트가 오면 좌석은 판매하지 않고 결제를 `REFUND_REQUIRED`로 기록합니다(DECISIONS.md D-10). 사용자용 결제 상태 조회 API는 없으므로(NOT-DONE), 운영자는 아래 SQL로 대상을 찾아 결제 제공자에 환불을 요청합니다. 환불에 따른 cancelled 이벤트가 오면 결제는 CANCELLED로 끝납니다.

```sql
SELECT p.payment_id, p.hold_id, h.user_id, p.status, p.updated_at
FROM payment p JOIN hold h ON h.id = p.hold_id
WHERE p.status = 'REFUND_REQUIRED'
ORDER BY p.updated_at;
```

## 부하 시험

### 시나리오와 실행 방법

- 공연 1개 × 좌석 1,000석(초기 데이터), 좌석 선점 `POST /holds` **총 5,000건**, **동시 200개** 요청
- 요청마다 좌석 1-4개(무작위), 요청의 60%가 인기 구간(A열, 좌석 id 1-100)을 노림. 실제 비율은 실행마다 60.0-60.5%
- 요청마다 다른 사용자(`X-User-Id`), 선점 유지 시간은 기본값 5분이라 시험 중에는 만료되지 않음
- 부하 생성기: [load-test/LoadTest.java](load-test/LoadTest.java)(JDK `HttpClient`, 플랫폼 스레드 200개가 요청을 하나씩 가져가 동기 전송). 별도 설치 없이 JDK 21로 실행
- 실행할 때마다 DB 볼륨을 지우고(`docker compose down -v`) 앱을 새로 띄운 뒤 시작

```bat
docker compose down -v
docker compose up -d
gradlew.bat bootRun
:: 다른 터미널에서
java load-test/LoadTest.java http://localhost:8080 5000 200 0.6
:: 시험 후 불변식 확인
docker exec -i seat-mariadb mariadb -useat -pseat seat < load-test/verify.sql
```

### 필수 사항이 지켜졌는지

네 번 실행 모두 `load-test/verify.sql`로 확인했습니다.

- 같은 좌석에 만료되지 않은 선점이 둘 이상 연결된 경우: **0행**
- 부분 선점(유효한 선점이 기록한 좌석 수와 실제로 잡은 좌석 수가 다른 경우): **0행**
- 201 응답 수 = DB의 유효한 선점 수 (예: 실행 1에서 460 = 460, HELD 좌석 816석, A열 100석 모두 선점)
- 오류 응답은 모두 409 `SEAT_UNAVAILABLE`. 5xx, 락 충돌(`LOCK_CONFLICT`), 데드락은 0건

### 결과

측정 조건: 노트북 1대(Intel i7-1355U 12스레드, 메모리 32GB, Windows)에서 부하 생성기·앱(`bootRun`)·MariaDB 11.4(Docker Desktop)를 함께 실행. 10/4 15:16-15:40. 커넥션 풀 크기만 바꿔 두 번씩 실행했습니다.

| 실행 | 커넥션 풀 | 처리량(요청/초) | 평균 | p50 | p95 | p99 | 최대 | 201 / 409 | DB 행 락 대기 (횟수, 합계, 최대) |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 10 (기본값) | 401.5 | 486ms | 389ms | 879ms | 2,592ms | 3,559ms | 460 / 4,540 | 181회, 0.47초, 36ms |
| 4 | 10 (기본값) | 451.5 | 432ms | 362ms | 778ms | 1,811ms | 2,777ms | 478 / 4,522 | 169회, 0.48초, 66ms |
| 2 | 50 | 233.0 | 813ms | 277ms | 3,164ms | 9,576ms | 10,944ms | 448 / 4,552 | 1,128회, 22.8초, 438ms |
| 3 | 50 | 480.1 | 385ms | 287ms | 822ms | 2,199ms | 3,165ms | 460 / 4,540 | 1,204회, 19.5초, 334ms |

### 측정 방법과 한계

- 지연: 클라이언트가 요청을 보내기 직전부터 응답 본문을 다 받을 때까지. 서버 처리 시간뿐 아니라 연결 대기와 클라이언트 쪽 대기가 함께 들어 있습니다.
- 처리량: 5,000건 / 첫 요청 시작부터 마지막 응답까지의 경과 시간. 시작 전 조회 요청 50건으로 워밍업했고, 이 요청은 측정에서 뺐습니다.
- p50·p95·p99·최대: 요청 5,000건 각각의 경과 시간을 정렬해 해당 순위의 값을 읽음. 생성 도구는 `load-test/LoadTest.java`.
- DB 행 락 대기: 시험 전후 `SHOW GLOBAL STATUS`의 `Innodb_row_lock_waits`, `Innodb_row_lock_time`, `Innodb_row_lock_time_max` (DB를 매번 새로 띄우므로 시험 후 값이 곧 시험 중의 값).
- 한계:
  - 부하 생성기·앱·DB가 같은 노트북에서 CPU를 나눠 씁니다.
  - 설정마다 두 번씩만 실행했고, 실행마다 편차가 큽니다(풀 50에서 233 / 480 요청/초).
  - 성공(201)은 460-478건뿐이고 5,000건 중 90% 넘게는 409로 끝납니다. 즉 이 수치는 좌석이 대부분 찬 뒤의 거절 경로가 많이 섞인 결과입니다.
  - 앱의 커넥션 대기 시간은 직접 재지 않았습니다(아래 병목 판단은 DB 쪽 지표와 풀 크기를 바꾼 비교에서 추정한 것).

### 가장 먼저 확인된 병목

**인기 구간 좌석 행에 대한 락 경합이고, 기본 설정에서는 이것이 커넥션 풀(10개) 앞의 대기로 드러납니다.**

- 풀 10에서는 실행 중 DB 행 락 대기 합계가 0.47초뿐인데, 요청 지연은 평균 430-490ms였습니다. 동시 200개 요청이 커넥션 10개를 기다리는 시간이 지연의 대부분이라고 봅니다(DB 최대 동시 연결 11 = 풀 10 + 측정용 1).
- 풀을 50으로 늘리면 행 락 대기가 약 40배(합계 0.47초 → 19.5-22.8초) 늘었고, 처리량은 오르지 않았습니다(233 / 480 요청/초, 풀 10은 402 / 452). 커넥션 대기가 줄어든 만큼 같은 인기 좌석 행을 동시에 갱신하려는 대기가 DB 안으로 옮겨 간 것으로 해석했습니다.
- 그래서 커넥션 풀을 키우는 것만으로는 개선되지 않고, 인기 구간 좌석을 동시에 갱신하려는 요청 자체를 줄이는 방법이 다음 검토 대상입니다. 이번 과제에서는 구현하지 않았습니다.
- 다만 설정마다 두 번씩만 실행했고 풀 50의 두 결과가 크게 달라(233 / 480 요청/초), 이 판단은 병목 후보에 대한 해석이지 확정적인 성능 결론은 아닙니다.
- 실행 중 한 번은 이전에 멈춘 부하 스크립트가 같은 앱에 겹쳐 요청을 보내 결과가 오염되어 버렸고, DB를 초기화해 다시 실행했습니다(위 표의 실행 3).
