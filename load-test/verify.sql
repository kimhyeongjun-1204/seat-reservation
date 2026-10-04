-- 부하 시험 후 불변식 확인 (공연 1)
-- 1) 같은 좌석에 만료되지 않은 선점이 둘 이상 연결된 경우 → 0행이어야 한다
SELECT hs.seat_id, COUNT(*) AS active_holds
FROM hold_seat hs JOIN hold h ON h.id = hs.hold_id
WHERE h.status = 'ACTIVE' AND h.expires_at > NOW(6)
GROUP BY hs.seat_id
HAVING COUNT(*) > 1;

-- 2) 부분 선점: 유효한 선점의 hold_seat 좌석 수와, 실제로 그 선점이 잡고 있는 좌석 수가 다른 경우 → 0행이어야 한다
SELECT h.id, COUNT(DISTINCT hs.seat_id) AS recorded, COUNT(DISTINCT s.id) AS held
FROM hold h
JOIN hold_seat hs ON hs.hold_id = h.id
LEFT JOIN seat s ON s.id = hs.seat_id AND s.hold_id = h.id AND s.status = 'HELD' AND s.hold_expires_at > NOW(6)
WHERE h.performance_id = 1 AND h.status = 'ACTIVE' AND h.expires_at > NOW(6)
GROUP BY h.id
HAVING recorded <> held;

-- 3) 요약: 유효한 선점 수, HELD 좌석 수, 그중 인기 구간(A열) 좌석 수
SELECT
  (SELECT COUNT(*) FROM hold WHERE performance_id = 1 AND status = 'ACTIVE' AND expires_at > NOW(6)) AS active_holds,
  (SELECT COUNT(*) FROM seat WHERE performance_id = 1 AND status = 'HELD' AND hold_expires_at > NOW(6)) AS held_seats,
  (SELECT COUNT(*) FROM seat WHERE performance_id = 1 AND status = 'HELD' AND hold_expires_at > NOW(6) AND id <= 100) AS held_hot_seats;
