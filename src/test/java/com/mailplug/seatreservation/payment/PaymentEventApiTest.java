package com.mailplug.seatreservation.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mailplug.seatreservation.TestcontainersConfiguration;
import com.mailplug.seatreservation.hold.HoldService;
import com.mailplug.seatreservation.support.TestFixtures;
import com.mailplug.seatreservation.support.TestFixtures.PerformanceFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 결제 이벤트 수신 API: 입력 검증, 전이표, 연결 규칙, 만료 후 승인 (CLAUDE.md D-8). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PaymentEventApiTest {

	static final String OCCURRED_AT = "2026-08-12T12:00:00.123456Z";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	HoldService holdService;

	TestFixtures fx;
	PerformanceFixture perf;
	String holdId;
	String paymentId;

	@BeforeEach
	void setUp() {
		fx = new TestFixtures(jdbc);
		perf = fx.performanceWithSeats(4);
		holdId = holdService.hold("user-a", perf.performanceId(), List.of(perf.seat(0), perf.seat(1))).holdId();
		paymentId = "pay-" + UUID.randomUUID();
	}

	// ---------- 입력 검증 (D-8 0단계): DB에 가기 전에 400 ----------

	@Test
	void 잘못된_status는_400이고_이벤트_행이_생기지_않는다() throws Exception {
		String eventId = newEventId();
		// 두 번 보내도 두 번째가 DUPLICATE(200)가 되지 않는다
		for (int i = 0; i < 2; i++) {
			send(eventId, paymentId, holdId, "approve", OCCURRED_AT)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		assertThat(fx.paymentEventCount(eventId)).isZero();
		assertThat(fx.paymentStatus(paymentId)).isNull();
		assertThat(fx.seatCountByStatus(holdId, "HELD")).isEqualTo(2);
	}

	@Test
	void 식별자가_없거나_컬럼_길이를_넘으면_400() throws Exception {
		send(null, paymentId, holdId, "approved", OCCURRED_AT).andExpect(status().isBadRequest());
		send(" ", paymentId, holdId, "approved", OCCURRED_AT).andExpect(status().isBadRequest());
		send("e".repeat(65), paymentId, holdId, "approved", OCCURRED_AT).andExpect(status().isBadRequest());
		send(newEventId(), "p".repeat(65), holdId, "approved", OCCURRED_AT).andExpect(status().isBadRequest());
		send(newEventId(), paymentId, "h".repeat(37), "approved", OCCURRED_AT).andExpect(status().isBadRequest());
		send(newEventId(), null, holdId, "approved", OCCURRED_AT)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(fx.paymentStatus(paymentId)).isNull();
	}

	@Test
	void occurredAt이_없거나_형식이_틀리거나_TIMESTAMP_범위_밖이면_400() throws Exception {
		send(newEventId(), paymentId, holdId, "approved", null).andExpect(status().isBadRequest());
		send(newEventId(), paymentId, holdId, "approved", "2026-08-12 12:00:00")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		send(newEventId(), paymentId, holdId, "approved", "2040-01-01T00:00:00Z").andExpect(status().isBadRequest());
		assertThat(fx.paymentStatus(paymentId)).isNull();
	}

	// ---------- 승인 ----------

	@Test
	void 유효한_선점에_approved가_오면_좌석이_SOLD가_되고_결제는_APPROVED() throws Exception {
		String eventId = newEventId();

		send(eventId, paymentId, holdId, "approved", OCCURRED_AT)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventId").value(eventId))
				.andExpect(jsonPath("$.result").value("APPLIED"))
				.andExpect(jsonPath("$.paymentStatus").value("APPROVED"));

		assertThat(fx.seatCountByStatus(holdId, "SOLD")).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat WHERE hold_id = ? AND hold_expires_at IS NULL",
				Integer.class, holdId)).isEqualTo(2);
		assertThat(fx.holdStatus(holdId)).isEqualTo("CONFIRMED");
		assertThat(fx.paymentStatus(paymentId)).isEqualTo("APPROVED");
		assertThat(fx.paymentEventResult(eventId)).isEqualTo("APPLIED");
		// occurredAt은 세션 시간대와 무관하게 같은 절대 시각으로 기록된다
		assertThat(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(occurred_at) FROM payment_event WHERE event_id = ?",
				BigDecimal.class, eventId)).isEqualByComparingTo("1786536000.123456");
	}

	@Test
	void 같은_eventId가_다시_오면_DUPLICATE이고_아무것도_바꾸지_않는다() throws Exception {
		String eventId = newEventId();
		send(eventId, paymentId, holdId, "approved", OCCURRED_AT).andExpect(status().isOk());

		send(eventId, paymentId, holdId, "approved", OCCURRED_AT)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("DUPLICATE"))
				.andExpect(jsonPath("$.paymentStatus").value("APPROVED"));

		assertThat(fx.paymentEventCount(eventId)).isEqualTo(1);
		assertThat(fx.paymentEventResult(eventId)).isEqualTo("APPLIED");
	}

	@Test
	void 없는_holdId면_404이고_이벤트_기록도_남지_않는다() throws Exception {
		String eventId = newEventId();

		send(eventId, paymentId, UUID.randomUUID().toString(), "approved", OCCURRED_AT)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));

		assertThat(fx.paymentEventCount(eventId)).isZero();
		assertThat(fx.paymentStatus(paymentId)).isNull();
	}

	@Test
	void 만료된_선점에_approved가_오면_REFUND_REQUIRED이고_좌석은_SOLD가_아니다() throws Exception {
		fx.expireHold(holdId);

		send(newEventId(), paymentId, holdId, "approved", OCCURRED_AT)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("APPLIED"))
				.andExpect(jsonPath("$.paymentStatus").value("REFUND_REQUIRED"));

		assertThat(fx.seatCountByStatus(holdId, "SOLD")).isZero();
		assertThat(fx.holdStatus(holdId)).isEqualTo("ACTIVE");
		// 다른 사용자가 바로 다시 선점할 수 있다
		holdService.hold("user-b", perf.performanceId(), List.of(perf.seat(0)));
	}

	@Test
	void 사용자가_취소한_선점에_approved가_오면_REFUND_REQUIRED() throws Exception {
		holdService.cancel("user-a", holdId);

		send(newEventId(), paymentId, holdId, "approved", OCCURRED_AT)
				.andExpect(jsonPath("$.paymentStatus").value("REFUND_REQUIRED"));

		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");
		assertThat(fx.holdStatus(holdId)).isEqualTo("CANCELLED");
	}

	@Test
	void APPROVED에_approved가_다시_오면_IGNORED() throws Exception {
		send(newEventId(), paymentId, holdId, "approved", OCCURRED_AT);
		String second = newEventId();

		send(second, paymentId, holdId, "approved", OCCURRED_AT)
				.andExpect(jsonPath("$.result").value("IGNORED"))
				.andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
		assertThat(fx.paymentEventResult(second)).isEqualTo("IGNORED");
	}

	// ---------- 취소와 순서 역전 ----------

	@Test
	void cancelled가_먼저_오고_approved가_나중에_와도_최종_CANCELLED이고_좌석은_AVAILABLE() throws Exception {
		send(newEventId(), paymentId, holdId, "cancelled", OCCURRED_AT)
				.andExpect(jsonPath("$.result").value("APPLIED"))
				.andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));
		// 선점이 유효했으므로 함께 해제된다
		assertThat(fx.holdStatus(holdId)).isEqualTo("CANCELLED");
		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");

		send(newEventId(), paymentId, holdId, "approved", OCCURRED_AT)
				.andExpect(jsonPath("$.result").value("IGNORED"))
				.andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));

		assertThat(fx.paymentStatus(paymentId)).isEqualTo("CANCELLED");
		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");
		assertThat(fx.seatStatus(perf.seat(1))).isEqualTo("AVAILABLE");
		assertThat(fx.holdStatus(holdId)).isEqualTo("CANCELLED");
	}

	@Test
	void APPROVED_후_cancelled가_오면_좌석이_AVAILABLE로_돌아간다() throws Exception {
		send(newEventId(), paymentId, holdId, "approved", OCCURRED_AT);

		send(newEventId(), paymentId, holdId, "cancelled", OCCURRED_AT)
				.andExpect(jsonPath("$.result").value("APPLIED"))
				.andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));

		for (int i = 0; i < 2; i++) {
			assertThat(fx.seatStatus(perf.seat(i))).isEqualTo("AVAILABLE");
			assertThat(fx.seatHoldId(perf.seat(i))).isNull();
		}
		assertThat(fx.holdStatus(holdId)).isEqualTo("CANCELLED");
		// 풀린 좌석은 다시 선점할 수 있다
		holdService.hold("user-b", perf.performanceId(), List.of(perf.seat(0)));
	}

	@Test
	void REFUND_REQUIRED_후_cancelled가_오면_CANCELLED() throws Exception {
		fx.expireHold(holdId);
		send(newEventId(), paymentId, holdId, "approved", OCCURRED_AT);

		send(newEventId(), paymentId, holdId, "cancelled", OCCURRED_AT)
				.andExpect(jsonPath("$.result").value("APPLIED"))
				.andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));
		assertThat(fx.seatCountByStatus(holdId, "SOLD")).isZero();
	}

	@Test
	void CANCELLED_후에는_어떤_이벤트도_IGNORED() throws Exception {
		send(newEventId(), paymentId, holdId, "cancelled", OCCURRED_AT);

		send(newEventId(), paymentId, holdId, "cancelled", OCCURRED_AT)
				.andExpect(jsonPath("$.result").value("IGNORED"))
				.andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));
	}

	// ---------- 연결 규칙: 결제 하나 ↔ 선점 하나 ----------

	@Test
	void 이미_다른_선점에_연결된_paymentId면_409이고_기록이_남지_않는다() throws Exception {
		send(newEventId(), paymentId, holdId, "approved", OCCURRED_AT);
		String otherHold = holdService.hold("user-b", perf.performanceId(), List.of(perf.seat(2))).holdId();
		String eventId = newEventId();

		send(eventId, paymentId, otherHold, "approved", OCCURRED_AT)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PAYMENT_CONFLICT"));

		assertThat(fx.paymentEventCount(eventId)).isZero();
		assertThat(fx.seatStatus(perf.seat(2))).isEqualTo("HELD");
	}

	@Test
	void 이미_다른_결제가_연결된_선점이면_409() throws Exception {
		send(newEventId(), paymentId, holdId, "approved", OCCURRED_AT);

		send(newEventId(), "pay-" + UUID.randomUUID(), holdId, "cancelled", OCCURRED_AT)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PAYMENT_CONFLICT"));

		assertThat(fx.paymentStatus(paymentId)).isEqualTo("APPROVED");
		assertThat(fx.seatCountByStatus(holdId, "SOLD")).isEqualTo(2);
	}

	// ---------- helpers ----------

	private static String newEventId() {
		return "evt-" + UUID.randomUUID();
	}

	private ResultActions send(String eventId, String paymentId, String holdId, String status, String occurredAt)
			throws Exception {
		return mvc.perform(post("/payment-events")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"eventId": %s, "paymentId": %s, "holdId": %s, "status": %s, "occurredAt": %s}
						""".formatted(json(eventId), json(paymentId), json(holdId), json(status), json(occurredAt))));
	}

	private static String json(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}
}
