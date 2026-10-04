# AI 사용 기록

## 사용한 인공지능 도구

| 도구 | 용도 |
|------|------|
| Claude (claude.ai, Claude Opus 5.5) | 설계 대안 비교, 문서 초안 작성, 설계 검토 |
| Claude Code | 저장소 안에서의 코드 작성·수정 (CLAUDE.md 지침 적용) |

## 인공지능 도구를 활용한 작업

- 개발 환경 구성 안내 (Spring Boot 4, MariaDB, Flyway, Testcontainers)
- 설계 대안 비교: 좌석 점유 판정 위치(seat 행 vs hold_seat UNIQUE), holdId 형식(BIGINT vs UUID v4/v7), 선점 취소 시 소유자 확인과 응답 코드
- DECISIONS.md 항목 초안과 CLAUDE.md '확정된 설계' 요약 작성
- DB 설계 문서(테이블 관계도, 컬럼 명세, 제약조건, Flyway V1 DDL 초안) 작성

대안 중 무엇을 채택할지는 비교 결과를 보고 직접 결정했고, AI가 제안한 쿼리·규칙은 요구사항 및 이미 확정한 설계와 대조해 검증한 뒤 반영했다.

## 결정 기록: 제안 출처와 내 결정 (저장소 구현 단계, 10/4)

제안 출처는 Claude Code(저장소 작업 세션), 검토용 Claude 채팅(claude.ai, 설계·코드 검토), 본인으로 나눈다. "직접 작성"은 내가 실제로 쓴 것에만 쓰고, 출처가 확실하지 않으면 "채택"으로 적는다.

| 결정 | 제안 출처 | 내가 한 일 |
|------|-----------|------------|
| 미정 컬럼: performance.starts_at 포함, hold.user_id VARCHAR(64), payment_event.result NULL 허용 + CHECK | Claude Code (선택지 제시) | 선택 |
| 키·제약 세부: seat.id AUTO_INCREMENT(초기 데이터만 id 명시), hold.created_at, hold.performance_id FK, hold_seat PK·인덱스·FK | Claude Code (선택지 제시) | 채택 (세부 조건을 덧붙여 답함) |
| INSERT IGNORE가 CHECK 위반도 0행으로 만드는 부작용 → INSERT 전 입력 검증(400)과 잘못된 status 테스트 | 검토용 Claude 채팅 (발견) | 채택 |
| 없는 공연 → 404, 외래 키 위반(1452) 변환은 hold INSERT 단계에만 적용 | 검토용 Claude 채팅 | 채택 |
| 엔티티는 조회·검증용, 쓰기는 모두 네이티브 쿼리, hold를 `save()`로 저장하지 않기 | 검토용 Claude 채팅 | 채택 |
| 추가 테스트 3개: 없는 공연 404이고 행 없음 / 다른 공연 좌석 409 / expiresAt이 DB 값과 정확히 일치 | 검토용 Claude 채팅 | 채택 |
| 응답 시각을 `UNIX_TIMESTAMP()`로 읽기 (세션 UTC 고정안 대신) | Claude Code (선택지 제시) | 선택 |
| 위 값을 BigDecimal로 받아 Instant로 변환 (double 금지), 시간대 테스트는 고치지 않기 | 검토용 Claude 채팅 | 채택 |
| 불변식 3 문구 수정, 선점 요청 멱등성은 NOT-DONE으로 | 검토용 Claude 채팅 (불변식 3과 미구현 항목의 충돌 지적) | 채택 |
| NOT-DONE.md 타협 항목 문구 | 검토용 Claude 채팅 (초안) | 채택 |
| 역검증: 취소의 `status = 'ACTIVE'`, 선점의 상태·만료 조건을 빼고 실행한 뒤 원복 확인 | 검토용 Claude 채팅 (제안) | 실행 결정 |
| 외부 설계 검토 결과 중 이번에 반영할 범위 (단언 보강, hold_seat VALUES 방식, 결제 잠금은 결제 단계로 미룸) | 검토용 Claude 채팅 (검토), Claude Code (현재 코드와 대조) | 반영 범위 선택 |
| 기능 단위 브랜치와 PR, 병합은 merge commit (squash 금지) | 검토용 Claude 채팅 (추천) | 채택 |
| DECISIONS D-4의 `@Version` 기각 이유 문장 | 검토용 Claude 채팅 (별도 검토 채팅이 작성) | 채택 |
| 결제 승인 시 좌석은 PK 오름차순 FOR UPDATE로 잠그되, payment는 FOR UPDATE로 읽지 않고 결제 트랜잭션만 READ COMMITTED로 실행 | 검토용 Claude 채팅 (payment FOR UPDATE의 갭 락 재발 지적) | 채택 |
| 검토 채팅 두 곳의 의견이 다를 때 항목별로 비교해 채택안 결정 (INSERT IGNORE 부작용, 없는 공연 404, 쓰기 방식) | 본인 | 비교·결정 |
| 결제 잠금 순서를 hold → seat(PK 오름차순) → payment(쓰기 시점)로 변경 | Claude Code (payment를 잠그지 않으면서 실제 순서가 바뀐 것을 지적) | 승인 |
| 결제 이벤트 트랜잭션만 READ COMMITTED (DECISIONS D-13) | 검토용 Claude 채팅 | 채택 |
| 커밋·push·병합: `cdc2908`·`c366e58`·`575c4ab`는 직접 커밋, `6b49d7a`·`731215d`는 지시에 따라 Claude Code가 실행, PR #1·#2 병합은 직접 | 본인 | 실행·지시 |

## 수정하거나 사용하지 않은 AI 제안

| 시점 | AI 제안 | 문제와 확인 방법 | 내 조치 |
|------|---------|------------------|---------|
| 10/3 | Spring Boot 3.x 사용 전제로 "4.x는 피하라"고 안내 | Spring Initializr에 3.x가 없음을 직접 확인 | 4.0.8 선택, 4.x 의존성 구조 그대로 사용 |
| 10/3 | 결제 이벤트 처리 규칙을 hold 상태만 축으로 한 상태 전이 표로 제안 | 과제 PDF의 "같은 paymentId의 approved/cancelled 순서 역전" 조건과 대조. 사용자의 선점 취소와 결제 취소(cancelled 이벤트)가 섞여 있고, cancelled가 먼저 도착하면 늦게 온 approved로 좌석이 SOLD로 되살아날 수 있음을 확인 | 상태 전이 표를 hold·payment 두 축으로 작성하기로 하고, "종료된 결제는 늦게 도착한 이벤트로 되살아나지 않음"을 불변식에 추가 |
| 10/3 | 선점 취소 조건을 `WHERE id = ? AND user_id = ? AND status = 'ACTIVE'`로 제안 | 확정된 지연 만료 규칙(D-1)과 대조한 결과, 만료됐지만 status가 ACTIVE인 선점도 취소가 성공해 "종료된 선점은 404" 규칙(D-3)과 어긋남 | 조건에 `AND expires_at > NOW(6)` 추가, "만료된 선점 취소 → 404" 테스트를 필수 테스트로 지정 |
| 10/4 | 결제 이벤트 처리 시 payment 행을 `FOR UPDATE`로 먼저 잠그도록 제안 | 첫 이벤트 시점에는 payment 행이 없어, 없는 행 잠금(갭 락) 후 동시 INSERT로 데드락이 날 수 있는 위험으로 설계 검토에서 판단 (재현하지 않음) | 항상 존재하는 hold 행을 먼저 잠그도록 변경, 잠금 순서를 hold → payment → seat로 통일 (최종 구현에서는 payment를 잠그고 읽지 않아 hold → seat(PK 오름차순) → payment(쓰기 시점), DECISIONS D-13) |
| 10/4 | 결제 이벤트 중복 판정에 `INSERT IGNORE` 후 영향 행 수 0이면 중복으로 보도록 제안 | INSERT IGNORE가 CHECK 위반 등 다른 오류도 경고로 바꿔 행을 건너뛸 수 있어, 잘못된 status가 중복(200)으로 오판될 수 있음을 스키마 질문 검토 중 확인. MariaDB 11.4에서 status 'approve'로 INSERT IGNORE 시 영향 행 0·행 없음·경고 4025(`ck_payment_event_status` failed)를 스키마 테스트로 확인 | INSERT 전에 애플리케이션 입력 검증(400)을 두기로 설계에 추가(CLAUDE.md D-8 0단계)하고, MariaDB 동작을 확인하는 DB 수준 테스트(`INSERT_IGNORE는_CHECK_위반을_중복과_같은_0행으로_만든다`)를 추가. 결제 이벤트 구현 때 `PaymentEventApiTest`에 잘못된 status → 400이고 이벤트 행이 생기지 않는(두 번 보내도 DUPLICATE가 아닌) 테스트를 추가 |
| 10/4 | 400 응답에 code를 붙이려고 `handleExceptionInternal`의 body 인자에 code를 넣도록 구현 | 400 응답에 code가 빠져 테스트 6건이 실패해 확인 | super 호출 결과의 본문에 code를 붙이도록 수정, 수정 후 6건 통과 |
| 10/4 | 선점 응답의 만료 시각을 hold 엔티티의 Instant 필드로 읽도록 구현 | 앱 연결의 세션 시간대를 +09:00으로 둔 시간대 테스트에서 응답 시각이 DB 값보다 9시간(32,400초) 늦게 나와 실패해 확인 | 응답 시각은 `UNIX_TIMESTAMP()`로 읽어 BigDecimal에서 Instant로 바꾸기로 결정, 테스트는 고치지 않고 통과 확인 (DECISIONS D-12, NOT-DONE 기록) |
| 10/4 | 테스트 주석에 "취소 조건에서 `status = 'ACTIVE'`가 빠지면 이 테스트만 잡아낸다"고 작성 | 해당 조건을 실제로 빼고 실행하자 확정된 선점 취소 테스트와 재취소 테스트 2개가 실패해, "이 테스트만"이 틀린 것을 확인 | 주석을 실제 결과("빠지면 204가 된다")에 맞게 수정 |
| 10/4 | 승인과 사용자 취소의 경합 테스트를 두 작업이 지연 없이 동시에 출발하도록 작성 | 단언은 통과했지만 측정 출력에서 30회 모두 취소가 먼저여서 승인이 먼저 잠그는 경우를 시험하지 못한 것을 확인. 취소만 0~20ms 늦추자 반대로 30회 모두 승인이 먼저였음 | 두 작업의 출발을 각각 0~4ms 무작위로 늦추고, 두 경우가 모두 나왔는지 단언 추가 (당시 실행: 승인 우선 6, 취소 우선 24. 1회 실행 값이며 10/4 16:08 재실행에서는 승인 우선 1, 취소 우선 29) |
| 10/4 | 부하 시험의 첫 병목을 "커넥션 풀(10개) 대기"로 추정 | 풀을 50으로 늘려 같은 시나리오를 비교하자 처리량은 실행마다 편차가 커 일관된 변화를 확인하지 못했고(233 / 480 요청/초, 풀 10은 402 / 452) DB 행 락 대기 합계가 0.47초에서 19.5-22.8초로 늘어, 커넥션 풀 대기만으로는 설명되지 않아 추정이 불완전했다고 판단 | 병목 후보를 "인기 좌석 행의 락 경합(기본 설정에서는 커넥션 풀 앞 대기로 드러남)"으로 고치고, 실행 횟수가 적어 확정적 결론이 아님을 README에 명시 |
| 10/4 | 부하 시험 반복 실행을 셸 스크립트로 자동화 | 스크립트가 `gradlew.bat`을 찾지 못해 앱이 뜨지 않았고, 멈춘 줄 알았던 스크립트가 남아 다음 실행에 부하를 겹쳐 보낸 것을 선점 생성 시각(사용자 번호가 두 바퀴 돎)으로 확인 | 오염된 실행 결과를 버리고, 남은 프로세스가 없는 것을 확인한 뒤 수동 순서로 다시 실행 |
