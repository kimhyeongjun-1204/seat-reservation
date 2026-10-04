package com.mailplug.seatreservation.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mailplug.seatreservation.TestcontainersConfiguration;
import com.mailplug.seatreservation.hold.HoldService;
import com.mailplug.seatreservation.support.TestFixtures;
import com.mailplug.seatreservation.support.TestFixtures.PerformanceFixture;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 락 대기 초과(MariaDB 1205)가 API 응답으로 어떻게 바뀌는지 확인한다.
 * 앱 연결의 innodb_lock_wait_timeout을 1초로 줄이고, 별도 연결이 행 락을 쥔 상태에서 요청을 보낸다.
 * - 선점 API: 409 LOCK_CONFLICT (D-4)
 * - 결제 이벤트: 503 LOCK_CONFLICT (재전송을 받기 위한 일시 오류)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 1")
@AutoConfigureMockMvc
class LockTimeoutResponseTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	HoldService holdService;

	@Test
	void 선점_중_락_대기_초과는_409_LOCK_CONFLICT() throws Exception {
		PerformanceFixture perf = new TestFixtures(jdbc).performanceWithSeats(1);

		try (Connection blocker = dataSource.getConnection(); Statement st = blocker.createStatement()) {
			blocker.setAutoCommit(false);
			st.executeQuery("SELECT id FROM seat WHERE id = " + perf.seat(0) + " FOR UPDATE");

			mvc.perform(post("/holds")
							.header("X-User-Id", "user-a")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"performanceId\": %d, \"seatIds\": [%d]}"
									.formatted(perf.performanceId(), perf.seat(0))))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("LOCK_CONFLICT"));

			blocker.rollback();
		}
	}

	@Test
	void 결제_이벤트_중_락_대기_초과는_503_LOCK_CONFLICT이고_이벤트_기록이_남지_않는다() throws Exception {
		PerformanceFixture perf = new TestFixtures(jdbc).performanceWithSeats(1);
		String holdId = holdService.hold("user-a", perf.performanceId(), List.of(perf.seat(0))).holdId();
		String eventId = "evt-" + UUID.randomUUID();

		try (Connection blocker = dataSource.getConnection(); Statement st = blocker.createStatement()) {
			blocker.setAutoCommit(false);
			st.executeQuery("SELECT id FROM hold WHERE id = '" + holdId + "' FOR UPDATE");

			mvc.perform(post("/payment-events")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"eventId": "%s", "paymentId": "pay-%s", "holdId": "%s",
									 "status": "approved", "occurredAt": "2026-08-12T12:00:00Z"}
									""".formatted(eventId, UUID.randomUUID(), holdId)))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("LOCK_CONFLICT"));

			blocker.rollback();
		}
		assertThat(new TestFixtures(jdbc).paymentEventCount(eventId)).isZero();
	}
}
