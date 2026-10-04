package com.mailplug.seatreservation.support;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 테스트마다 자기 공연·좌석을 만든다 (초기 데이터에 의존하지 않음).
 * 각 테스트가 새 공연을 쓰므로 테스트 간 정리 없이 격리된다.
 */
public class TestFixtures {

	private final JdbcTemplate jdbc;

	public TestFixtures(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record PerformanceFixture(long performanceId, List<Long> seatIds) {
		public long seat(int index) {
			return seatIds.get(index);
		}
	}

	public PerformanceFixture performanceWithSeats(int seatCount) {
		var keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"INSERT INTO performance (title, starts_at) VALUES (?, NOW(6) + INTERVAL 1 DAY)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, "test-" + UUID.randomUUID());
			return ps;
		}, keys);
		long performanceId = keys.getKey().longValue();

		jdbc.batchUpdate("INSERT INTO seat (performance_id, seat_no) VALUES (?, ?)",
				java.util.stream.IntStream.rangeClosed(1, seatCount)
						.mapToObj(i -> new Object[] {performanceId, "T-" + i})
						.toList());

		List<Long> seatIds = jdbc.queryForList(
				"SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, performanceId);
		return new PerformanceFixture(performanceId, seatIds);
	}

	/** 선점과 그 좌석의 만료 시각을 DB 시각 기준 과거로 옮긴다 (실제로 기다리지 않음). */
	public void expireHold(String holdId) {
		jdbc.update("UPDATE hold SET expires_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", holdId);
		jdbc.update("""
				UPDATE seat SET hold_expires_at = NOW(6) - INTERVAL 1 SECOND
				WHERE hold_id = ? AND status = 'HELD'
				""", holdId);
	}

	/** 결제 확정 상태를 직접 만든다 (결제 이벤트 기능 구현 전 테스트용). */
	public void markSold(String holdId) {
		jdbc.update("UPDATE seat SET status = 'SOLD', hold_expires_at = NULL WHERE hold_id = ? AND status = 'HELD'",
				holdId);
		jdbc.update("UPDATE hold SET status = 'CONFIRMED' WHERE id = ?", holdId);
	}

	public String seatStatus(long seatId) {
		return jdbc.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, seatId);
	}

	public String seatHoldId(long seatId) {
		return jdbc.queryForObject("SELECT hold_id FROM seat WHERE id = ?", String.class, seatId);
	}

	public String holdStatus(String holdId) {
		return jdbc.queryForObject("SELECT status FROM hold WHERE id = ?", String.class, holdId);
	}

	public int holdCount(long performanceId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM hold WHERE performance_id = ?", Integer.class,
				performanceId);
	}

	/** 선점과 그 좌석의 만료 시각을 DB 시각 기준으로 지금부터 millis 뒤로 옮긴다 (경계 시각 경합용). */
	public void expireHoldIn(String holdId, long millis) {
		jdbc.update("UPDATE hold SET expires_at = NOW(6) + INTERVAL ? MICROSECOND WHERE id = ?", millis * 1000, holdId);
		jdbc.update("""
				UPDATE seat SET hold_expires_at = (SELECT expires_at FROM hold WHERE id = ?)
				WHERE hold_id = ? AND status = 'HELD'
				""", holdId, holdId);
	}

	public String paymentStatus(String paymentId) {
		List<String> rows = jdbc.queryForList("SELECT status FROM payment WHERE payment_id = ?", String.class,
				paymentId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public int paymentEventCount(String eventId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM payment_event WHERE event_id = ?", Integer.class, eventId);
	}

	public String paymentEventResult(String eventId) {
		return jdbc.queryForObject("SELECT result FROM payment_event WHERE event_id = ?", String.class, eventId);
	}

	public int seatCountByStatus(String holdId, String status) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM seat WHERE hold_id = ? AND status = ?", Integer.class,
				holdId, status);
	}

	public int holdSeatCount(long performanceId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM hold_seat hs JOIN hold h ON h.id = hs.hold_id
				WHERE h.performance_id = ?
				""", Integer.class, performanceId);
	}
}
