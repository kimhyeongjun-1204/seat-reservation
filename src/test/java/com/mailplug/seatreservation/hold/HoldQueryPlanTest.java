package com.mailplug.seatreservation.hold;

import com.mailplug.seatreservation.TestcontainersConfiguration;
import com.mailplug.seatreservation.seat.SeatRepository;
import com.mailplug.seatreservation.support.TestFixtures;
import com.mailplug.seatreservation.support.TestFixtures.PerformanceFixture;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 선점 seat UPDATE의 실행 계획을 출력한다 (D-4 확인 항목).
 * PRIMARY 범위 스캔이 아니면 RR에서 잠금 범위가 넓어지고 잠금 순서가 PK 순서와 달라질 수 있다.
 * 쿼리는 SeatRepository.holdSeats의 @Query 문자열을 그대로 가져와 파라미터만 리터럴로 바꾼다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class HoldQueryPlanTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 선점_UPDATE의_실행_계획을_출력한다() throws Exception {
		// 초기 데이터와 같은 규모(공연 1개 × 1,000석)를 따로 만든다
		PerformanceFixture perf = new TestFixtures(jdbc).performanceWithSeats(1000);
		List<Long> seatIds = List.of(perf.seat(0), perf.seat(1), perf.seat(50), perf.seat(99));

		String sql = SeatRepository.class
				.getMethod("holdSeats", long.class, Collection.class, String.class)
				.getAnnotation(Query.class).value()
				.replace(":holdId", "'" + UUID.randomUUID() + "'")
				.replace(":performanceId", Long.toString(perf.performanceId()))
				.replace(":seatIds", seatIds.stream().map(String::valueOf).collect(Collectors.joining(",")));

		jdbc.execute("ANALYZE TABLE seat");
		System.out.println("[PLAN] seat 전체 행 수 = "
				+ jdbc.queryForObject("SELECT COUNT(*) FROM seat", Long.class));
		System.out.println("[PLAN] SQL =\n" + sql.strip());
		for (var row : jdbc.queryForList("EXPLAIN " + sql)) {
			System.out.println("[PLAN] EXPLAIN " + row);
		}
		System.out.println("[PLAN] EXPLAIN FORMAT=JSON\n"
				+ jdbc.queryForObject("EXPLAIN FORMAT=JSON " + sql, String.class));
	}
}
