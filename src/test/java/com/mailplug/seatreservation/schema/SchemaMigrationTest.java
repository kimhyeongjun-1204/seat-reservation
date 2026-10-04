package com.mailplug.seatreservation.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mailplug.seatreservation.TestcontainersConfiguration;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * V1(스키마)·V2(초기 데이터) 마이그레이션 검증.
 * 각 테스트는 트랜잭션 안에서 자기 공연·좌석을 만들고 끝나면 롤백한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class SchemaMigrationTest {

	@Autowired
	JdbcTemplate jdbc;

	long performanceId;
	String holdId;

	@BeforeEach
	void setUp() {
		performanceId = insertPerformance();
		holdId = insertHold(performanceId);
	}

	// --- ck_seat_state ---

	@Test
	void 유효한_좌석_상태_조합은_저장된다() {
		assertThatCode(() -> {
			insertSeat("T-1", "AVAILABLE", null, false);
			insertSeat("T-2", "HELD", holdId, true);
			insertSeat("T-3", "SOLD", holdId, false);
		}).doesNotThrowAnyException();
	}

	@Test
	void HELD인데_hold_id가_NULL이면_거부된다() {
		assertThatThrownBy(() -> insertSeat("T-1", "HELD", null, true))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_seat_state");
	}

	@Test
	void SOLD인데_만료시각이_있으면_거부된다() {
		assertThatThrownBy(() -> insertSeat("T-1", "SOLD", holdId, true))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_seat_state");
	}

	@Test
	void AVAILABLE인데_hold_id가_있으면_거부된다() {
		assertThatThrownBy(() -> insertSeat("T-1", "AVAILABLE", holdId, false))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_seat_state");
	}

	@Test
	void 정의되지_않은_좌석_상태는_거부된다() {
		assertThatThrownBy(() -> insertSeat("T-1", "EXPIRED", null, false))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_seat_state");
	}

	// --- payment ---

	@Test
	void 한_선점에_두_번째_결제를_연결하면_거부된다() {
		insertPayment("pay-" + UUID.randomUUID(), holdId);

		assertThatThrownBy(() -> insertPayment("pay-" + UUID.randomUUID(), holdId))
				.isInstanceOf(DuplicateKeyException.class);
	}

	// --- payment_event ---

	@Test
	void payment_event_잘못된_status는_일반_INSERT에서_거부된다() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO payment_event (event_id, payment_id, hold_id, status, occurred_at)
				VALUES (?, 'pay-1', ?, 'approve', NOW(6))
				""", "evt-" + UUID.randomUUID(), holdId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * D-8 1단계의 INSERT IGNORE가 CHECK 위반을 어떻게 다루는지 관찰한다.
	 * 영향 행 0 + 행 없음이면 "중복(DUPLICATE)"과 구분되지 않으므로 애플리케이션 입력 검증(400)이 필수다.
	 */
	@Test
	void INSERT_IGNORE는_CHECK_위반을_중복과_같은_0행으로_만든다() {
		String eventId = "evt-" + UUID.randomUUID();

		int affected = jdbc.update("""
				INSERT IGNORE INTO payment_event (event_id, payment_id, hold_id, status, occurred_at)
				VALUES (?, 'pay-1', ?, 'approve', NOW(6))
				""", eventId, holdId);
		var warnings = jdbc.queryForList("SHOW WARNINGS"); // 다음 문장이 실행되면 경고가 지워지므로 바로 조회
		Integer rows = jdbc.queryForObject(
				"SELECT COUNT(*) FROM payment_event WHERE event_id = ?", Integer.class, eventId);
		System.out.println("[ENV] INSERT IGNORE + CHECK 위반: affected=" + affected + ", rows=" + rows
				+ ", warnings=" + warnings);

		assertThat(affected).isZero();
		assertThat(rows).isZero();
	}

	// --- V2 초기 데이터 ---

	@Test
	void 초기_데이터는_공연_1개에_좌석_1000석이고_A열이_100석이다() {
		Integer seats = jdbc.queryForObject(
				"SELECT COUNT(*) FROM seat WHERE performance_id = 1", Integer.class);
		Integer rowA = jdbc.queryForObject(
				"SELECT COUNT(*) FROM seat WHERE performance_id = 1 AND seat_no LIKE 'A-%'", Integer.class);
		Integer rowAIdRange = jdbc.queryForObject(
				"SELECT COUNT(*) FROM seat WHERE performance_id = 1 AND seat_no LIKE 'A-%' AND id BETWEEN 1 AND 100",
				Integer.class);
		Integer available = jdbc.queryForObject(
				"SELECT COUNT(*) FROM seat WHERE performance_id = 1 AND status = 'AVAILABLE'", Integer.class);

		assertThat(seats).isEqualTo(1000);
		assertThat(rowA).isEqualTo(100);
		assertThat(rowAIdRange).isEqualTo(100);
		assertThat(available).isEqualTo(1000);
		assertThat(jdbc.queryForObject("SELECT seat_no FROM seat WHERE id = 1", String.class)).isEqualTo("A-1");
		assertThat(jdbc.queryForObject("SELECT seat_no FROM seat WHERE id = 1000", String.class)).isEqualTo("J-100");
	}

	// --- 환경 확인 ---

	@Test
	void innodb_snapshot_isolation_값을_출력한다() {
		try {
			String value = jdbc.queryForObject("SELECT @@innodb_snapshot_isolation", String.class);
			System.out.println("[ENV] @@innodb_snapshot_isolation = " + value);
		} catch (DataAccessException e) {
			System.out.println("[ENV] @@innodb_snapshot_isolation 조회 실패: " + e.getMostSpecificCause().getMessage());
		}
		System.out.println("[ENV] @@version = " + jdbc.queryForObject("SELECT @@version", String.class));
	}

	// --- helpers ---

	private long insertPerformance() {
		var keys = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"INSERT INTO performance (title, starts_at) VALUES (?, NOW(6) + INTERVAL 1 DAY)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, "schema-test");
			return ps;
		}, keys);
		return keys.getKey().longValue();
	}

	private String insertHold(long performanceId) {
		String id = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO hold (id, performance_id, user_id, status, expires_at)
				VALUES (?, ?, 'user-1', 'ACTIVE', NOW(6) + INTERVAL 300 SECOND)
				""", id, performanceId);
		return id;
	}

	private void insertSeat(String seatNo, String status, String holdId, boolean withExpiry) {
		jdbc.update("""
				INSERT INTO seat (performance_id, seat_no, status, hold_id, hold_expires_at)
				VALUES (?, ?, ?, ?, IF(?, NOW(6) + INTERVAL 300 SECOND, NULL))
				""", performanceId, seatNo, status, holdId, withExpiry);
	}

	private void insertPayment(String paymentId, String holdId) {
		jdbc.update("INSERT INTO payment (payment_id, hold_id, status) VALUES (?, ?, 'APPROVED')",
				paymentId, holdId);
	}
}
