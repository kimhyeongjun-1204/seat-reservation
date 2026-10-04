-- 공연 1개 × 좌석 1,000석 (A-1 ~ J-100, id 1 ~ 1000). A열(id 1~100)이 부하 시험의 인기 구간 (D-6)
-- starts_at 리터럴은 마이그레이션 세션 시간대로 해석된다 (값 자체는 로직에서 사용하지 않음)

INSERT INTO performance (id, title, starts_at)
VALUES (1, 'Sample Concert', '2026-12-31 19:00:00.000000');

-- seq_0_to_999: MariaDB Sequence 엔진의 가상 테이블
INSERT INTO seat (id, performance_id, seat_no, status)
SELECT seq + 1,
       1,
       CONCAT(CHAR(ASCII('A') + seq DIV 100), '-', seq MOD 100 + 1),
       'AVAILABLE'
FROM seq_0_to_999;
