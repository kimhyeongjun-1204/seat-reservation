# CLAUDE.md

## 프로젝트
공연 좌석 예약 시스템 (백엔드 사전 과제). 요구사항 원문은 docs/assignment.pdf (git 미포함).
짧은 시간에 같은 공연으로 동시 요청이 몰리고, 여러 서버가 하나의 DB를 공유하는 상황을 가정한다.

## 기술 스택
- Java 21, Spring Boot 4.0.8, Spring Data JPA(Hibernate 7), MariaDB 11.4, Flyway, Gradle(Groovy)
- 테스트: JUnit 5 + Testcontainers 2.x(MariaDB). 동시성 테스트는 ExecutorService + CountDownLatch
- Boot 4 기준으로 작성: Jackson 3(tools.jackson), Testcontainers 2.x 패키지, @MockitoBean.
  Boot 3 방식과 달라서 수정한 부분이 있으면 보고할 것

## 실행 환경 (Windows)
- 로컬 DB: `docker compose up -d` (localhost:3307, DB/계정 seat/seat)
- 테스트: `gradlew.bat test` (Docker Desktop 실행 필요)
- 앱 실행: `gradlew.bat bootRun`

## 설계 원칙
- 정합성은 DB로만 보장한다: 유니크 제약조건, 조건부 UPDATE, 트랜잭션, 락.
  애플리케이션 메모리 락, synchronized, 단일 프로세스 상태에 의존하지 않는다
- 스키마와 초기 데이터는 Flyway 마이그레이션(src/main/resources/db/migration)으로만 관리한다. ddl-auto는 validate 유지
- 만료 시간 등 시간 관련 값은 설정으로 조정 가능하게 하고, 테스트에서 실제로 기다리지 않는다

## 작업 방식
- 테스트를 먼저 작성하고, 실패하는 것을 확인한 뒤 구현한다
- 실패 케이스를 반드시 포함한다: 동시 중복 선점, 다중 좌석 부분 실패 롤백, 이벤트 중복 수신, approved/cancelled 순서 역전, 만료와 승인 경합
- 테스트가 실패할 때 H2 전환, 테스트 비활성화, ddl-auto 변경, 단언 완화로 우회하지 않는다. 에러 원문과 원인 추정을 보고하고 판단을 기다린다
- 설계 결정(테이블 구조, 상태 전이, 우선순위 규칙)을 임의로 정하지 않는다. 아래 '확정된 설계'를 따르고, 없는 내용은 먼저 질문한다
- 커밋하지 않는다. 변경 사항은 사용자가 확인 후 직접 커밋한다

## 문서
- README.md: 실행·테스트 방법, 초기 데이터 준비 방법. 실제로 측정한 값만 기록한다
- DECISIONS.md: 항목마다 선택한 방법 / 검토한 대안 / 장단점 (3~5문장)
- NOT-DONE.md: 구현하지 못한 것과 알고 감수한 타협
- AI-USAGE.md: AI 제안이 컴파일 오류·테스트 실패·요구사항 불일치로 틀린 것이 확인되면
  표(시점 / AI 제안 / 문제와 확인 방법 / 내 조치)에 추가할 한 줄을 보고에 포함한다

## 보고 형식
- 실행한 명령과 결과(성공/실패, 핵심 로그 한두 줄)
- 변경·생성·삭제한 파일 목록
- NOT-DONE.md 또는 AI-USAGE.md에 추가할 후보가 있으면 함께 제시

## 확정된 설계
### D-1. 점유 판정 기준: seat 행 (A안)
- seat.status ∈ {AVAILABLE, HELD, SOLD} + seat.hold_id + seat.hold_expires_at. 점유 판정은 seat 행만 본다
- hold(user_id, status, expires_at)와 hold_seat(선점-좌석 매핑)는 기록·조회용. hold.status가 ACTIVE여도 만료 시각이 지났으면 만료로 취급
- ck_seat_state CHECK: AVAILABLE이면 hold_id·만료시각 NULL / HELD면 둘 다 NOT NULL / SOLD면 hold_id NOT NULL
- 선점·취소·확정 = 조건부 UPDATE + 영향받은 행 수 검사. 불일치 시 트랜잭션 롤백
- 선점 순서(seat.hold_id FK 때문): hold INSERT → seat UPDATE → hold_seat INSERT, 한 트랜잭션
- 만료 판정은 WHERE 절의 `NOW(6)` 비교 (지연 만료). 앱 서버 시계(Instant.now())로 판정 금지
- 만료 테스트는 TTL 설정값을 줄이거나 만료 시각 컬럼을 과거로 직접 UPDATE해서 작성
- seat와 hold를 함께 바꾸는 작업은 반드시 한 트랜잭션
- 금지: seat UPDATE에서 status·만료 조건 생략 (CHECK가 막지 못하는 유일한 구멍)
- 주의: Hibernate 7은 @Enumerated(STRING)을 MariaDB 네이티브 ENUM으로 기대할 수 있음 → VARCHAR 컬럼과 validate 불일치 시 @JdbcTypeCode(SqlTypes.VARCHAR)로 해결, 발생하면 AI-USAGE.md에 기록

### D-2. holdId: UUID v4 문자열
- 애플리케이션에서 UUID.randomUUID()로 생성, VARCHAR(36). INSERT 전에 ID 확정
- 추측 방지 목적일 뿐 인가가 아님 → D-3과 함께 사용

### D-3. 선점 취소
- DELETE /holds/{holdId} + X-User-Id 헤더
- hold: UPDATE hold SET status = 'CANCELLED' WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND expires_at > NOW(6)
- 영향받은 행 0이면 404 (주인 아님·없음·이미 취소·확정·만료 모두 동일)
- 성공 시 같은 트랜잭션에서 seat 해제: UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL WHERE hold_id = ? AND status = 'HELD'
- 필수 테스트: 다른 사용자의 취소 → 404, 좌석 HELD 유지 / 만료된 선점 취소 → 404

### D-4. 선점 쿼리·락 방식
- 요청 검증: seatIds가 비었거나 중복이 있으면 400 (DB 접근 전)
- 한 트랜잭션: hold INSERT → seat 다중 UPDATE 한 문장 → hold_seat INSERT
- 만료 시각은 hold INSERT에서 한 번만 계산: expires_at = NOW(6) + INTERVAL :ttl SECOND
- seat UPDATE (만료 시각은 hold 값을 복사):
  UPDATE seat SET status = 'HELD', hold_id = :holdId,
  hold_expires_at = (SELECT expires_at FROM hold WHERE id = :holdId)
  WHERE performance_id = :performanceId AND id IN (:seatIds)
  AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= NOW(6)))
- 영향받은 행 수 ≠ seatIds 개수 → 예외 → 전체 롤백 → 409 (어느 좌석이 원인인지는 응답하지 않음)
- 존재하지 않는 좌석·다른 공연 좌석이 섞여도 행 수 불일치로 보고 409 (경합과 구분하지 않음)
  - 시간이 남으면 실패 시에만 존재 여부를 COUNT로 확인해 404로 구분(C안). 못 하면 NOT-DONE에 기록
- 데드락(1213)·락 대기 초과(1205)는 재시도 없이 409
- 격리 수준은 기본값(REPEATABLE READ) 유지
- 같은 트랜잭션에서 seat 엔티티를 미리 조회하지 않는다 (네이티브 UPDATE는 영속성 컨텍스트를 거치지 않아 값이 어긋남)
- 확인할 것: EXPLAIN으로 PRIMARY 범위 스캔인지, 테스트 컨테이너에서 @@innodb_snapshot_isolation 값
- 필수 테스트: 같은 좌석 동시 선점 → 1건만 성공 / 일부 좌석 실패 → 전부 롤백(hold·hold_seat 행 없음) /
  {1,2}와 {2,1} 교차 동시 요청 → 불변식 유지, 데드락 횟수 측정 / 만료된 HELD 재선점 성공, 유효한 HELD·SOLD 선점 실패 /
  중복 seatId → 400

## 미정
- request_key: V1에 컬럼·UNIQUE(user_id, request_key)만 선반영, 구현은 결제 이벤트 이후 (확정 대기)
- 만료 보조 작업 여부 (3번)
- payment 테이블과 결제 이벤트 규칙 (4번)
- SOLD 좌석의 hold_expires_at 처리(NULL로 비울지 유지할지)와 ck_seat_state 반영 — 4번과 함께 결정

## 불변식 (모든 설계·테스트는 이걸 깨뜨리려는 방향으로 검증)
- 한 좌석에 만료되지 않은 점유(HELD) 또는 SOLD는 최대 1개
- 하나의 선점 요청은 모든 좌석 성공 또는 전부 실패 (부분 선점 없음)
- 같은 선점 요청·같은 eventId는 몇 번 와도 한 번만 반영
- 결제 하나(paymentId)는 hold 하나에만 연결
- 종료 상태가 된 결제는 늦게 도착한 이벤트로 되살아나지 않음 (순서 역전 대비)
- SOLD 좌석은 문서화한 결제 취소 규칙 외에는 다른 상태로 바뀌지 않음
- 만료 판정은 DB 시각(NOW(6)) 기준, 앱 서버 시계로 판정하지 않음

