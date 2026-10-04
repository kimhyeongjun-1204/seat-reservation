package com.mailplug.seatreservation.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.mailplug.seatreservation.TestcontainersConfiguration;
import com.mailplug.seatreservation.hold.HoldService;
import com.mailplug.seatreservation.support.TestFixtures;
import com.mailplug.seatreservation.support.TestFixtures.PerformanceFixture;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** occurredAt을 +09:00 세션에서 저장해도 같은 절대 시각으로 기록된다 (D-12와 같은 원칙: FROM_UNIXTIME). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.datasource.hikari.connection-init-sql=SET time_zone = '+09:00'")
class PaymentEventTimeZoneTest {

	@Autowired
	PaymentEventService paymentEventService;

	@Autowired
	HoldService holdService;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 플러스9시_세션에서_저장한_occurredAt이_같은_절대_시각이다() {
		assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+09:00");
		PerformanceFixture perf = new TestFixtures(jdbc).performanceWithSeats(1);
		String holdId = holdService.hold("user-a", perf.performanceId(), List.of(perf.seat(0))).holdId();
		String eventId = "evt-" + UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-08-12T12:00:00.123456Z");

		paymentEventService.handle(new PaymentEventCommand(eventId, "pay-" + UUID.randomUUID(), holdId, "approved",
				occurredAt));

		BigDecimal stored = jdbc.queryForObject(
				"SELECT UNIX_TIMESTAMP(occurred_at) FROM payment_event WHERE event_id = ?", BigDecimal.class, eventId);
		System.out.println("[MEASURE] +09:00 세션 occurredAt 저장값(epoch) = " + stored);
		assertThat(stored).isEqualByComparingTo("1786536000.123456");
	}
}
