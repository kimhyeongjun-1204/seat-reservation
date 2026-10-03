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
(설계 논의 후 채움)