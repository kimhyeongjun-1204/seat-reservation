package com.mailplug.seatreservation.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.mailplug.seatreservation.TestcontainersConfiguration;
import com.mailplug.seatreservation.seat.SeatQueryService;
import com.mailplug.seatreservation.support.TestFixtures;
import com.mailplug.seatreservation.support.TestFixtures.PerformanceFixture;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 세션 시간대가 다른 서버(연결)끼리도 만료 판정이 같아야 한다 (D-6, TIMESTAMP(6) 선택 이유).
 * 앱의 모든 연결은 '+09:00'으로 열고, 판정은 '+00:00'으로 바꾼 별도 연결에서 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.datasource.hikari.connection-init-sql=SET time_zone = '+09:00'")
class HoldTimeZoneTest {

	@Autowired
	HoldService holdService;

	@Autowired
	SeatQueryService seatQueryService;

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 플러스9시_세션에서_선점하고_UTC_세션에서_판정해도_결과가_같다() throws Exception {
		assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+09:00");
		PerformanceFixture perf = new TestFixtures(jdbc).performanceWithSeats(1);
		long seatId = perf.seat(0);

		HoldResult hold = holdService.hold("user-a", perf.performanceId(), List.of(seatId));

		// +09:00 세션과 +00:00 세션에서 같은 판정·같은 절대 시각
		Judgement kst = judge(seatId, "+09:00");
		Judgement utc = judge(seatId, "+00:00");
		System.out.println("[MEASURE] +09:00 판정 " + kst + " / +00:00 판정 " + utc);
		assertThat(kst.valid()).isTrue();
		assertThat(utc.valid()).isTrue();
		assertThat(utc.expiresAtEpochMicros()).isEqualTo(kst.expiresAtEpochMicros());
		assertThat(utc.remainingSeconds()).isBetween(295L, 300L);
		assertThat(kst.remainingSeconds()).isBetween(295L, 300L);
		// 응답의 expiresAt(Instant)도 같은 절대 시각
		assertThat(hold.expiresAt().getEpochSecond() * 1_000_000 + hold.expiresAt().getNano() / 1000)
				.isEqualTo(utc.expiresAtEpochMicros());

		// UTC 세션에서 만료시키면 +09:00 세션(앱)에서도 만료로 판정된다
		try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
			st.execute("SET time_zone = '+00:00'");
			st.executeUpdate("UPDATE hold SET expires_at = NOW(6) - INTERVAL 1 SECOND WHERE id = '%s'"
					.formatted(hold.holdId()));
			st.executeUpdate("UPDATE seat SET hold_expires_at = NOW(6) - INTERVAL 1 SECOND WHERE id = " + seatId);
			st.execute("SET time_zone = '+09:00'");
		}
		assertThat(seatQueryService.getSeats(perf.performanceId()).get(0).status()).isEqualTo("AVAILABLE");
		assertThat(holdService.hold("user-b", perf.performanceId(), List.of(seatId)).holdId()).isNotBlank();
	}

	record Judgement(boolean valid, long remainingSeconds, long expiresAtEpochMicros) {}

	private Judgement judge(long seatId, String timeZone) throws Exception {
		try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
			st.execute("SET time_zone = '" + timeZone + "'");
			try (ResultSet rs = st.executeQuery("""
					SELECT hold_expires_at > NOW(6),
					       TIMESTAMPDIFF(SECOND, NOW(6), hold_expires_at),
					       UNIX_TIMESTAMP(hold_expires_at) * 1000000
					FROM seat WHERE id = %d
					""".formatted(seatId))) {
				rs.next();
				var result = new Judgement(rs.getBoolean(1), rs.getLong(2), rs.getBigDecimal(3).longValue());
				st.execute("SET time_zone = '+09:00'"); // 풀로 돌아가는 연결을 원래 설정으로 복구
				return result;
			}
		}
	}
}
