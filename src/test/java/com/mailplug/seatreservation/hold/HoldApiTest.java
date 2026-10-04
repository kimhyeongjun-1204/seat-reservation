package com.mailplug.seatreservation.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mailplug.seatreservation.TestcontainersConfiguration;
import com.mailplug.seatreservation.support.TestFixtures;
import com.mailplug.seatreservation.support.TestFixtures.PerformanceFixture;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;
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

/** 좌석 조회·선점·선점 취소 API (D-3, D-4, D-5, D-7). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HoldApiTest {

	static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
	static final String ISO_UTC_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	TestFixtures fx;
	PerformanceFixture perf;

	@BeforeEach
	void setUp() {
		fx = new TestFixtures(jdbc);
		perf = fx.performanceWithSeats(12);
	}

	// ---------- 좌석 조회 ----------

	@Test
	void 좌석_조회는_id순으로_상태를_돌려주고_선점_정보는_노출하지_않는다() throws Exception {
		mvc.perform(get("/performances/{id}/seats", perf.performanceId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(12)))
				.andExpect(jsonPath("$[0].seatId").value(perf.seat(0)))
				.andExpect(jsonPath("$[0].seatNo").value("T-1"))
				.andExpect(jsonPath("$[0].status").value("AVAILABLE"))
				.andExpect(jsonPath("$[0].holdId").doesNotExist())
				.andExpect(jsonPath("$[0].userId").doesNotExist());
	}

	@Test
	void 없는_공연의_좌석_조회는_404() throws Exception {
		mvc.perform(get("/performances/{id}/seats", Long.MAX_VALUE))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
	}

	@Test
	void 선점된_좌석은_HELD_판매된_좌석은_SOLD로_조회된다() throws Exception {
		holdId(hold("user-a", perf.seat(0)));
		String sold = holdId(hold("user-b", perf.seat(1)));
		fx.markSold(sold);

		mvc.perform(get("/performances/{id}/seats", perf.performanceId()))
				.andExpect(jsonPath("$[0].status").value("HELD"))
				.andExpect(jsonPath("$[1].status").value("SOLD"))
				.andExpect(jsonPath("$[2].status").value("AVAILABLE"));
	}

	@Test
	void 만료_시각이_지난_HELD_좌석은_조회에서_AVAILABLE로_나온다() throws Exception {
		String holdId = holdId(hold("user-a", perf.seat(0)));
		fx.expireHold(holdId);

		mvc.perform(get("/performances/{id}/seats", perf.performanceId()))
				.andExpect(jsonPath("$[0].status").value("AVAILABLE"));
		// 지연 만료: DB 행은 그대로 HELD로 남아 있다 (D-5)
		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("HELD");
	}

	// ---------- 선점 성공 ----------

	@Test
	void 선점에_성공하면_201과_holdId_만료시각을_돌려주고_좌석이_HELD가_된다() throws Exception {
		Instant dbNow = dbNow();

		var result = hold("user-a", perf.seat(0), perf.seat(1))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.holdId").value(matchesPattern(UUID_PATTERN)))
				.andExpect(jsonPath("$.performanceId").value(perf.performanceId()))
				.andExpect(jsonPath("$.seatIds", contains((int) perf.seat(0), (int) perf.seat(1))))
				.andExpect(jsonPath("$.expiresAt").value(matchesPattern(ISO_UTC_PATTERN)));

		String body = result.andReturn().getResponse().getContentAsString();
		String holdId = JsonPath.read(body, "$.holdId");
		Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));

		// 응답의 만료 시각 = DB 시각 + TTL(5분). 앱 서버 시계가 아니라 DB 값을 그대로 돌려줘야 한다
		assertThat(expiresAt).isBetween(dbNow.plusSeconds(295), dbNow.plusSeconds(305));
		long responseMicros = expiresAt.getEpochSecond() * 1_000_000 + expiresAt.getNano() / 1000;
		assertThat(epochMicros("SELECT UNIX_TIMESTAMP(expires_at) FROM hold WHERE id = ?", holdId))
				.isEqualTo(responseMicros);
		assertThat(epochMicros("SELECT UNIX_TIMESTAMP(hold_expires_at) FROM seat WHERE id = ?", perf.seat(0)))
				.isEqualTo(responseMicros);
		assertThat(epochMicros("SELECT UNIX_TIMESTAMP(hold_expires_at) FROM seat WHERE id = ?", perf.seat(1)))
				.isEqualTo(responseMicros);

		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("HELD");
		assertThat(fx.seatHoldId(perf.seat(1))).isEqualTo(holdId);
		assertThat(fx.holdStatus(holdId)).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT user_id FROM hold WHERE id = ?", String.class, holdId))
				.isEqualTo("user-a");
		assertThat(fx.holdSeatCount(perf.performanceId())).isEqualTo(2);
		// seat.hold_expires_at은 hold.expires_at을 복사한 값 (D-4)
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM seat s JOIN hold h ON h.id = s.hold_id
				WHERE h.id = ? AND s.hold_expires_at = h.expires_at
				""", Integer.class, holdId)).isEqualTo(2);
	}

	@Test
	void 만료된_HELD_좌석은_다른_사용자가_다시_선점할_수_있다() throws Exception {
		String first = holdId(hold("user-a", perf.seat(0)));
		fx.expireHold(first);

		String second = holdId(hold("user-b", perf.seat(0)).andExpect(status().isCreated()));

		assertThat(fx.seatHoldId(perf.seat(0))).isEqualTo(second);
	}

	// ---------- 선점 실패: 409 ----------

	@Test
	void 유효하게_선점된_좌석은_선점할_수_없다() throws Exception {
		String first = holdId(hold("user-a", perf.seat(0)));

		hold("user-b", perf.seat(0))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));
		assertThat(fx.seatHoldId(perf.seat(0))).isEqualTo(first);
	}

	@Test
	void 판매된_좌석은_선점할_수_없다() throws Exception {
		String sold = holdId(hold("user-a", perf.seat(0)));
		fx.markSold(sold);

		hold("user-b", perf.seat(0))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));
		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("SOLD");
		assertThat(fx.seatHoldId(perf.seat(0))).isEqualTo(sold);
	}

	@Test
	void 일부_좌석이_선점돼_있으면_전부_롤백된다() throws Exception {
		String first = holdId(hold("user-a", perf.seat(1)));
		String expiresSql = "SELECT UNIX_TIMESTAMP(hold_expires_at) FROM seat WHERE id = ?";
		long firstExpiresMicros = epochMicros(expiresSql, perf.seat(1));

		hold("user-b", perf.seat(0), perf.seat(1), perf.seat(2))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));

		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");
		assertThat(fx.seatStatus(perf.seat(2))).isEqualTo("AVAILABLE");
		// 원래 선점은 hold_id·만료 시각 모두 그대로다
		assertThat(fx.seatStatus(perf.seat(1))).isEqualTo("HELD");
		assertThat(fx.seatHoldId(perf.seat(1))).isEqualTo(first);
		assertThat(epochMicros(expiresSql, perf.seat(1))).isEqualTo(firstExpiresMicros);
		// 실패한 요청의 hold·hold_seat 행이 남지 않는다
		assertThat(fx.holdCount(perf.performanceId())).isEqualTo(1);
		assertThat(fx.holdSeatCount(perf.performanceId())).isEqualTo(1);
	}

	@Test
	void 존재하지_않는_좌석이_섞이면_409이고_전부_롤백된다() throws Exception {
		hold("user-a", perf.seat(0), Long.MAX_VALUE)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));

		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");
		assertThat(fx.holdCount(perf.performanceId())).isZero();
	}

	@Test
	void 다른_공연의_좌석이_섞이면_409이고_전부_롤백된다() throws Exception {
		PerformanceFixture other = fx.performanceWithSeats(1);

		hold("user-a", perf.seat(0), other.seat(0))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));

		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");
		assertThat(fx.seatStatus(other.seat(0))).isEqualTo("AVAILABLE");
		assertThat(fx.holdCount(perf.performanceId())).isZero();
	}

	@Test
	void 없는_공연으로_선점하면_404() throws Exception {
		mvc.perform(post("/holds")
						.header("X-User-Id", "user-a")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"performanceId\": %d, \"seatIds\": [%d]}".formatted(Long.MAX_VALUE, perf.seat(0))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hold WHERE performance_id = ?", Integer.class,
				Long.MAX_VALUE)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hold_seat WHERE seat_id = ?", Integer.class,
				perf.seat(0))).isZero();
	}

	// ---------- 선점 요청 검증: 400 ----------

	@Test
	void 중복된_seatId는_400() throws Exception {
		hold("user-a", perf.seat(0), perf.seat(0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(fx.holdCount(perf.performanceId())).isZero();
	}

	@Test
	void 빈_seatIds는_400() throws Exception {
		hold("user-a")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void seatIds가_10개를_넘으면_400() throws Exception {
		hold("user-a", perf.seatIds().subList(0, 11).stream().mapToLong(Long::longValue).toArray())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(fx.holdCount(perf.performanceId())).isZero();
	}

	@Test
	void seatIds가_10개면_성공한다() throws Exception {
		hold("user-a", perf.seatIds().subList(0, 10).stream().mapToLong(Long::longValue).toArray())
				.andExpect(status().isCreated());
	}

	@Test
	void X_User_Id가_없으면_400() throws Exception {
		mvc.perform(post("/holds")
						.contentType(MediaType.APPLICATION_JSON)
						.content(holdBody(perf.seat(0))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void X_User_Id가_공백이거나_64자를_넘으면_400() throws Exception {
		hold(" ", perf.seat(0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		hold("u".repeat(65), perf.seat(0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(fx.holdCount(perf.performanceId())).isZero();
	}

	@Test
	void performanceId가_없거나_본문이_깨지면_400() throws Exception {
		mvc.perform(post("/holds").header("X-User-Id", "user-a")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"seatIds\": [%d]}".formatted(perf.seat(0))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(post("/holds").header("X-User-Id", "user-a")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"performanceId\": "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(post("/holds").header("X-User-Id", "user-a")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"performanceId\": %d, \"seatIds\": [null]}".formatted(perf.performanceId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	// ---------- 선점 취소 (D-3) ----------

	@Test
	void 본인의_유효한_선점을_취소하면_204이고_좌석이_풀린다() throws Exception {
		String holdId = holdId(hold("user-a", perf.seat(0), perf.seat(1)));

		cancel("user-a", holdId).andExpect(status().isNoContent());

		assertThat(fx.holdStatus(holdId)).isEqualTo("CANCELLED");
		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");
		assertThat(fx.seatHoldId(perf.seat(1))).isNull();
		assertThat(jdbc.queryForObject("SELECT hold_expires_at FROM seat WHERE id = ?", Object.class, perf.seat(1)))
				.isNull();
		// 취소 후 다른 사용자가 바로 선점할 수 있다
		hold("user-b", perf.seat(0)).andExpect(status().isCreated());
	}

	@Test
	void 다른_사용자의_선점_취소는_404이고_좌석은_HELD로_남는다() throws Exception {
		String holdId = holdId(hold("user-a", perf.seat(0)));

		cancel("user-b", holdId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));

		assertThat(fx.holdStatus(holdId)).isEqualTo("ACTIVE");
		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("HELD");
		assertThat(fx.seatHoldId(perf.seat(0))).isEqualTo(holdId);
	}

	@Test
	void 만료된_선점_취소는_404() throws Exception {
		String holdId = holdId(hold("user-a", perf.seat(0)));
		fx.expireHold(holdId);

		cancel("user-a", holdId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
		assertThat(fx.holdStatus(holdId)).isEqualTo("ACTIVE");
	}

	@Test
	void 만료_후_다른_사용자가_재선점한_좌석은_원래_주인의_취소로_풀리지_않는다() throws Exception {
		String first = holdId(hold("user-a", perf.seat(0)));
		fx.expireHold(first);
		String second = holdId(hold("user-b", perf.seat(0)));

		cancel("user-a", first).andExpect(status().isNotFound());

		assertThat(fx.seatHoldId(perf.seat(0))).isEqualTo(second);
		assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("HELD");
	}

	@Test
	void 이미_취소한_선점을_다시_취소하면_404() throws Exception {
		String holdId = holdId(hold("user-a", perf.seat(0)));
		cancel("user-a", holdId).andExpect(status().isNoContent());

		cancel("user-a", holdId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
	}

	@Test
	void 확정된_선점_취소는_404이고_SOLD가_유지된다() throws Exception {
		String holdId = holdId(hold("user-a", perf.seat(0), perf.seat(1)));
		fx.markSold(holdId); // 확정 상태는 SQL로 만든다 (hold CONFIRMED, 좌석 SOLD)
		// 확정된 hold도 expires_at은 미래이므로, 취소 조건에서 status = 'ACTIVE'가 빠지면 204가 된다 (역검증으로 확인)
		assertThat(jdbc.queryForObject("SELECT expires_at > NOW(6) FROM hold WHERE id = ?", Boolean.class, holdId))
				.isTrue();

		cancel("user-a", holdId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));

		assertThat(fx.holdStatus(holdId)).isEqualTo("CONFIRMED");
		for (int i = 0; i < 2; i++) {
			assertThat(fx.seatStatus(perf.seat(i))).isEqualTo("SOLD");
			assertThat(fx.seatHoldId(perf.seat(i))).isEqualTo(holdId);
		}
	}

	@Test
	void 없는_holdId_취소는_404() throws Exception {
		cancel("user-a", java.util.UUID.randomUUID().toString())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
	}

	@Test
	void 취소_요청에_X_User_Id가_없으면_400() throws Exception {
		String holdId = holdId(hold("user-a", perf.seat(0)));

		mvc.perform(delete("/holds/{holdId}", holdId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(fx.holdStatus(holdId)).isEqualTo("ACTIVE");
	}

	// ---------- helpers ----------

	/** TIMESTAMP 값을 세션 시간대와 무관한 epoch 마이크로초로 읽는다. */
	private long epochMicros(String sql, Object arg) {
		return jdbc.queryForObject(sql, java.math.BigDecimal.class, arg).movePointRight(6).longValue();
	}

	private Instant dbNow() {
		var micros = jdbc.queryForObject("SELECT UNIX_TIMESTAMP(NOW(6))", java.math.BigDecimal.class)
				.movePointRight(6).longValue();
		return Instant.EPOCH.plus(micros, java.time.temporal.ChronoUnit.MICROS);
	}

	private ResultActions hold(String userId, long... seatIds) throws Exception {
		return mvc.perform(post("/holds")
				.header("X-User-Id", userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(holdBody(seatIds)));
	}

	private String holdBody(long... seatIds) {
		String ids = Arrays.stream(seatIds).mapToObj(Long::toString).collect(Collectors.joining(","));
		return "{\"performanceId\": %d, \"seatIds\": [%s]}".formatted(perf.performanceId(), ids);
	}

	private ResultActions cancel(String userId, String holdId) throws Exception {
		return mvc.perform(delete("/holds/{holdId}", holdId).header("X-User-Id", userId));
	}

	private static String holdId(ResultActions result) throws Exception {
		String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.holdId");
	}
}
