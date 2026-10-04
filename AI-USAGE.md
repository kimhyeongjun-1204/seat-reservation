# AI 사용 기록

## 사용한 인공지능 도구

| 도구 | 용도 |
|------|------|
| Claude (claude.ai, Claude Opus 5.5) | 설계 대안 비교, 문서 초안 작성, 설계 검토 |
| Claude Code (로컬 세션) | 저장소 안에서의 코드 작성·수정 (CLAUDE.md 지침 적용) |

## 인공지능 도구를 활용한 작업

- 개발 환경 구성 안내 (Spring Boot 4, MariaDB, Flyway, Testcontainers)
- 설계 대안 비교: 좌석 점유 판정 위치(seat 행 vs hold_seat UNIQUE), holdId 형식(BIGINT vs UUID v4/v7), 선점 취소 시 소유자 확인과 응답 코드
- DECISIONS.md 항목 초안과 CLAUDE.md '확정된 설계' 요약 작성
- DB 설계 문서(테이블 관계도, 컬럼 명세, 제약조건, Flyway V1 DDL 초안) 작성

대안 중 무엇을 채택할지는 비교 결과를 보고 직접 결정했고, AI가 제안한 쿼리·규칙은 요구사항 및 이미 확정한 설계와 대조해 검증한 뒤 반영했다.

## 수정하거나 사용하지 않은 AI 제안

| 시점 | AI 제안 | 문제와 확인 방법 | 내 조치 |
|------|---------|------------------|---------|
| 10/3 | Spring Boot 3.x 사용 전제로 "4.x는 피하라"고 안내 | Spring Initializr에 3.x가 없음을 직접 확인 | 4.0.8 선택, 4.x 의존성 구조 그대로 사용 |
| 10/3 | Claude Code 세션 종류(로컬/클라우드)를 구분하지 않고 작업 지시 | 클라우드 세션이 CLAUDE.md·과제 PDF를 읽지 못하고, CLAUDE.md에만 있던 "커밋 금지" 규칙을 몰라 별도 브랜치에 커밋·push한 것을 Claude Code 보고로 확인 | 브랜치를 main에 rebase로 합친 뒤 로컬 세션으로 전환해 재검증 |
| 10/3 | 결제 이벤트 처리 규칙을 hold 상태만 축으로 한 상태 전이 표로 제안 | 과제 PDF의 "같은 paymentId의 approved/cancelled 순서 역전" 조건과 대조. 사용자의 선점 취소와 결제 취소(cancelled 이벤트)가 섞여 있고, cancelled가 먼저 도착하면 늦게 온 approved로 좌석이 SOLD로 되살아날 수 있음을 확인 | 상태 전이 표를 hold·payment 두 축으로 작성하기로 하고, "종료된 결제는 늦게 도착한 이벤트로 되살아나지 않음"을 불변식에 추가 |
| 10/3 | 선점 취소 조건을 `WHERE id = ? AND user_id = ? AND status = 'ACTIVE'`로 제안 | 확정된 지연 만료 규칙(D-1)과 대조한 결과, 만료됐지만 status가 ACTIVE인 선점도 취소가 성공해 "종료된 선점은 404" 규칙(D-3)과 어긋남 | 조건에 `AND expires_at > NOW(6)` 추가, "만료된 선점 취소 → 404" 테스트를 필수 테스트로 지정 |
| 10/4 | 결제 이벤트 처리 시 payment 행을 `FOR UPDATE`로 먼저 잠그도록 제안 | 첫 이벤트 시점에는 payment 행이 없어, 없는 행 잠금(갭 락) 후 동시 INSERT로 데드락이 날 수 있음을 설계 검토에서 확인 | 항상 존재하는 hold 행을 먼저 잠그도록 변경, 잠금 순서를 hold → payment → seat로 통일 |
| 10/4 | 결제 이벤트 중복 판정에 `INSERT IGNORE` 후 영향 행 수 0이면 중복으로 보도록 제안 | INSERT IGNORE가 CHECK 위반 등 다른 오류도 경고로 바꿔 행을 건너뛸 수 있어, 잘못된 status가 중복(200)으로 오판될 수 있음을 스키마 질문 검토 중 확인. MariaDB 11.4에서 status 'approve'로 INSERT IGNORE 시 영향 행 0·행 없음·경고 4025(`ck_payment_event_status` failed)를 스키마 테스트로 확인 | INSERT 전에 애플리케이션 입력 검증(400)을 필수로 두고, 잘못된 status 이벤트 테스트를 추가 |
