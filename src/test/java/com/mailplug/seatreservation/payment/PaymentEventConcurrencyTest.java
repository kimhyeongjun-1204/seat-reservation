package com.mailplug.seatreservation.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.mailplug.seatreservation.TestcontainersConfiguration;
import com.mailplug.seatreservation.common.ApiException;
import com.mailplug.seatreservation.hold.HoldService;
import com.mailplug.seatreservation.support.Concurrent;
import com.mailplug.seatreservation.support.TestFixtures;
import com.mailplug.seatreservation.support.TestFixtures.PerformanceFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결제 이벤트의 동시 수신 (CLAUDE.md D-8 필수 테스트).
 * 결과는 서비스 반환값 또는 예외 종류로 분류하고, 예상하지 못한 예외는 테스트 실패로 드러낸다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentEventConcurrencyTest {

	static final Instant OCCURRED_AT = Instant.parse("2026-08-12T12:00:00Z");

	@Autowired
	PaymentEventService paymentEventService;

	@Autowired
	HoldService holdService;

	@Autowired
	JdbcTemplate jdbc;

	TestFixtures fx;

	@BeforeEach
	void setUp() {
		fx = new TestFixtures(jdbc);
	}

	/** 이벤트 처리 결과: APPLIED·IGNORED·DUPLICATE 또는 오류 코드(HOLD_NOT_FOUND, PAYMENT_CONFLICT, LOCK_CONFLICT). */
	record Outcome(String kind, String paymentStatus) {}

	@Test
	void 같은_eventId가_동시에_20번_와도_한_번만_반영된다() throws Exception {
		PerformanceFixture perf = fx.performanceWithSeats(2);
		String holdId = hold("user-a", perf, 0, 1);
		String paymentId = newPaymentId();
		String eventId = "evt-" + UUID.randomUUID();

		List<Callable<Outcome>> tasks = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tasks.add(() -> send(eventId, paymentId, holdId, "approved"));
		}
		List<Outcome> outcomes = Concurrent.run(tasks);

		System.out.println("[MEASURE] 같은 eventId 20건 동시: " + count(outcomes));
		assertThat(outcomes).filteredOn(o -> o.kind().equals("APPLIED")).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o.kind().equals("DUPLICATE")).hasSize(19);
		assertThat(fx.paymentEventCount(eventId)).isEqualTo(1);
		assertThat(fx.seatCountByStatus(holdId, "SOLD")).isEqualTo(2);
		assertThat(fx.holdStatus(holdId)).isEqualTo("CONFIRMED");
		assertThat(fx.paymentStatus(paymentId)).isEqualTo("APPROVED");
	}

	@Test
	void 서로_다른_선점_50개의_첫_approved가_동시에_와도_락_충돌_없이_모두_확정된다() throws Exception {
		PerformanceFixture perf = fx.performanceWithSeats(100);
		List<String> holdIds = new ArrayList<>();
		List<String> paymentIds = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			holdIds.add(hold("user-" + i, perf, 2 * i, 2 * i + 1));
			paymentIds.add(newPaymentId());
		}

		List<Callable<Outcome>> tasks = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			int idx = i;
			tasks.add(() -> send("evt-" + UUID.randomUUID(), paymentIds.get(idx), holdIds.get(idx), "approved"));
		}
		List<Outcome> outcomes = Concurrent.run(tasks);

		long sold = jdbc.queryForObject("SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'SOLD'",
				Long.class, perf.performanceId());
		System.out.println("[MEASURE] 서로 다른 선점 50개 첫 approved 동시: " + count(outcomes) + ", SOLD 좌석 " + sold
				+ "/100");
		assertThat(outcomes).filteredOn(o -> o.kind().equals("LOCK_CONFLICT")).isEmpty();
		assertThat(outcomes).allMatch(o -> o.kind().equals("APPLIED") && "APPROVED".equals(o.paymentStatus()));
		assertThat(sold).isEqualTo(100);
	}

	@Test
	void 같은_paymentId의_approved와_cancelled가_동시에_와도_최종_CANCELLED이고_좌석은_AVAILABLE() throws Exception {
		int rounds = 30;
		Map<String, Long> all = new java.util.TreeMap<>();
		for (int r = 0; r < rounds; r++) {
			PerformanceFixture perf = fx.performanceWithSeats(2);
			String holdId = hold("user-a", perf, 0, 1);
			String paymentId = newPaymentId();

			List<Outcome> outcomes = Concurrent.run(List.of(
					() -> send("evt-" + UUID.randomUUID(), paymentId, holdId, "approved"),
					() -> send("evt-" + UUID.randomUUID(), paymentId, holdId, "cancelled")));
			outcomes.forEach(o -> all.merge(o.kind(), 1L, Long::sum));

			// 순서와 관계없이 연결 충돌(409)이 나면 안 된다: 같은 결제의 두 이벤트다
			assertThat(outcomes).noneMatch(o -> o.kind().equals("PAYMENT_CONFLICT"));
			assertThat(outcomes).noneMatch(o -> o.kind().equals("LOCK_CONFLICT"));
			assertThat(fx.paymentStatus(paymentId)).isEqualTo("CANCELLED");
			assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");
			assertThat(fx.seatStatus(perf.seat(1))).isEqualTo("AVAILABLE");
			assertThat(fx.holdStatus(holdId)).isEqualTo("CANCELLED");
		}
		System.out.println("[MEASURE] 같은 결제 approved·cancelled 동시 " + rounds + "회: " + all);
	}

	@Test
	void 승인과_사용자_취소가_동시에_오면_데드락_없이_하나만_성공한다() throws Exception {
		int rounds = 30;
		int approvedWins = 0;
		int cancelWins = 0;
		Random random = new Random(11);
		for (int r = 0; r < rounds; r++) {
			PerformanceFixture perf = fx.performanceWithSeats(2);
			String holdId = hold("user-a", perf, 0, 1);
			String paymentId = newPaymentId();
			// 지연 없이 동시에 출발시키면 30회 모두 취소가 먼저 hold를 잠갔고, 취소만 0~20ms 늦추면 30회 모두 승인이 먼저였다(측정).
			// 양쪽 경우가 모두 나오도록 두 작업의 출발을 각각 0~4ms 무작위로 늦춘다
			long approvalDelayMs = random.nextInt(5);
			long cancelDelayMs = random.nextInt(5);

			List<Outcome> outcomes = Concurrent.run(List.of(
					() -> {
						Thread.sleep(approvalDelayMs);
						return send("evt-" + UUID.randomUUID(), paymentId, holdId, "approved");
					},
					() -> {
						Thread.sleep(cancelDelayMs);
						return cancel("user-a", holdId);
					}));
			Outcome approval = outcomes.get(0);
			Outcome cancel = outcomes.get(1);

			assertThat(outcomes).noneMatch(o -> o.kind().equals("LOCK_CONFLICT"));
			if (cancel.kind().equals("CANCELLED")) {
				cancelWins++;
				assertThat(approval.paymentStatus()).isEqualTo("REFUND_REQUIRED");
				assertThat(fx.seatStatus(perf.seat(0))).isEqualTo("AVAILABLE");
				assertThat(fx.holdStatus(holdId)).isEqualTo("CANCELLED");
			} else {
				approvedWins++;
				assertThat(cancel.kind()).isEqualTo("HOLD_NOT_FOUND");
				assertThat(approval.paymentStatus()).isEqualTo("APPROVED");
				assertThat(fx.seatCountByStatus(holdId, "SOLD")).isEqualTo(2);
				assertThat(fx.holdStatus(holdId)).isEqualTo("CONFIRMED");
			}
		}
		System.out.printf("[MEASURE] 승인 vs 사용자 취소 동시 %d회: 승인 우선 %d, 취소 우선 %d, LOCK_CONFLICT 0%n",
				rounds, approvedWins, cancelWins);
		// 양쪽 경우를 모두 시험했는지 확인 (한쪽만 나오면 경합을 시험하지 못한 것)
		assertThat(approvedWins).isPositive();
		assertThat(cancelWins).isPositive();
	}

	/**
	 * 만료 직전(DB 시각 기준 수백 ms 뒤)에 승인과 다른 사용자의 재선점이 겹치게 한다.
	 * 만료 시각을 과거로 바꾸면 결과가 매번 같아 경합을 시험하지 못하므로 실제 경계 근처에서 반복한다.
	 * 허용되는 결과는 둘 중 하나다:
	 *  (가) 좌석 SOLD ∧ 결제 APPROVED ∧ 재선점 실패
	 *  (나) 좌석 SOLD 아님 ∧ 결제 REFUND_REQUIRED (재선점이 성공했다면 좌석은 새 선점의 HELD)
	 */
	@Test
	void 만료_경계에서_승인과_재선점이_겹쳐도_SOLD와_새_HELD가_동시에_되지_않는다() throws Exception {
		int rounds = 30;
		Random random = new Random(7);
		Map<String, Long> summary = new java.util.TreeMap<>();
		int lockConflicts = 0;

		for (int r = 0; r < rounds; r++) {
			PerformanceFixture perf = fx.performanceWithSeats(2);
			String holdId = hold("user-a", perf, 0, 1);
			String paymentId = newPaymentId();
			fx.expireHoldIn(holdId, 300);
			long approvalDelayMs = 200 + random.nextInt(200); // 만료 시각 전후 ±100ms

			List<Outcome> outcomes = Concurrent.run(List.of(
					() -> {
						Thread.sleep(approvalDelayMs);
						return send("evt-" + UUID.randomUUID(), paymentId, holdId, "approved");
					},
					() -> rehold("user-b", perf, 600)));
			Outcome approval = outcomes.get(0);
			Outcome rehold = outcomes.get(1);
			lockConflicts += (int) outcomes.stream().filter(o -> o.kind().equals("LOCK_CONFLICT")).count();

			String seat0 = fx.seatStatus(perf.seat(0));
			String seat0Hold = fx.seatHoldId(perf.seat(0));
			if ("APPROVED".equals(approval.paymentStatus())) {
				summary.merge("(가) SOLD·APPROVED·재선점 실패", 1L, Long::sum);
				assertThat(seat0).isEqualTo("SOLD");
				assertThat(seat0Hold).isEqualTo(holdId);
				assertThat(fx.seatCountByStatus(holdId, "SOLD")).isEqualTo(2);
				assertThat(rehold.kind()).isNotEqualTo("HELD");
			} else {
				assertThat(approval.paymentStatus()).isEqualTo("REFUND_REQUIRED");
				assertThat(fx.seatCountByStatus(holdId, "SOLD")).isZero();
				if (rehold.kind().equals("HELD")) {
					summary.merge("(나) REFUND_REQUIRED·재선점 성공", 1L, Long::sum);
					assertThat(seat0).isEqualTo("HELD");
					assertThat(seat0Hold).isEqualTo(rehold.paymentStatus()); // 새 holdId
				} else {
					summary.merge("(나) REFUND_REQUIRED·재선점 시간 내 실패", 1L, Long::sum);
				}
			}
		}
		System.out.printf("[MEASURE] 만료 경계 승인 vs 재선점 %d회: %s, LOCK_CONFLICT %d건%n", rounds, summary, lockConflicts);
	}

	// ---------- helpers ----------

	private String hold(String userId, PerformanceFixture perf, int... seatIndexes) {
		List<Long> seatIds = java.util.Arrays.stream(seatIndexes).mapToObj(perf::seat).toList();
		return holdService.hold(userId, perf.performanceId(), seatIds).holdId();
	}

	private static String newPaymentId() {
		return "pay-" + UUID.randomUUID();
	}

	private Outcome send(String eventId, String paymentId, String holdId, String status) {
		try {
			PaymentEventResponse response = paymentEventService.handle(
					new PaymentEventCommand(eventId, paymentId, holdId, status, OCCURRED_AT));
			return new Outcome(response.result(), response.paymentStatus());
		} catch (ApiException e) {
			return new Outcome(e.getCode().name(), null);
		} catch (PessimisticLockingFailureException e) {
			return new Outcome("LOCK_CONFLICT", null);
		}
	}

	private Outcome cancel(String userId, String holdId) {
		try {
			holdService.cancel(userId, holdId);
			return new Outcome("CANCELLED", null);
		} catch (ApiException e) {
			return new Outcome(e.getCode().name(), null);
		} catch (PessimisticLockingFailureException e) {
			return new Outcome("LOCK_CONFLICT", null);
		}
	}

	/** 만료될 때까지 짧은 간격으로 재선점을 시도한다. 성공하면 kind=HELD, paymentStatus 자리에 새 holdId. */
	private Outcome rehold(String userId, PerformanceFixture perf, long budgetMs) throws InterruptedException {
		long deadline = System.nanoTime() + budgetMs * 1_000_000;
		String last = "SEAT_UNAVAILABLE";
		while (System.nanoTime() < deadline) {
			try {
				String newHold = holdService.hold(userId, perf.performanceId(), List.of(perf.seat(0), perf.seat(1)))
						.holdId();
				return new Outcome("HELD", newHold);
			} catch (ApiException e) {
				last = e.getCode().name();
			} catch (PessimisticLockingFailureException e) {
				return new Outcome("LOCK_CONFLICT", null);
			}
			Thread.sleep(10);
		}
		return new Outcome(last, null);
	}

	private static Map<String, Long> count(List<Outcome> outcomes) {
		return outcomes.stream().collect(Collectors.groupingBy(
				o -> o.kind() + (o.paymentStatus() == null ? "" : "/" + o.paymentStatus()),
				java.util.TreeMap::new, Collectors.counting()));
	}
}
