package com.mailplug.seatreservation.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.mailplug.seatreservation.TestcontainersConfiguration;
import com.mailplug.seatreservation.common.ApiException;
import com.mailplug.seatreservation.support.TestFixtures;
import com.mailplug.seatreservation.support.TestFixtures.PerformanceFixture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 좌석에 동시 요청이 몰릴 때의 불변식 (D-4).
 * - 한 좌석에 유효한 HELD는 최대 1개
 * - 한 선점 요청은 전부 성공 또는 전부 실패
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class HoldConcurrencyTest {

	@Autowired
	HoldService holdService;

	@Autowired
	JdbcTemplate jdbc;

	TestFixtures fx;

	@BeforeEach
	void setUp() {
		fx = new TestFixtures(jdbc);
	}

	enum Outcome { SUCCESS, SEAT_UNAVAILABLE, LOCK_CONFLICT }

	record Attempt(Outcome outcome, String holdId, List<Long> seatIds) {}

	@Test
	void 같은_좌석에_50명이_동시에_선점하면_1건만_성공한다() throws Exception {
		PerformanceFixture perf = fx.performanceWithSeats(1);
		long seatId = perf.seat(0);

		List<Attempt> attempts = runConcurrently(50,
				i -> () -> attempt("user-" + i, perf.performanceId(), List.of(seatId)));

		Map<Outcome, Long> counts = count(attempts);
		System.out.println("[MEASURE] 같은 좌석 50건 동시 선점: " + counts);

		List<Attempt> successes = successes(attempts);
		assertThat(successes).hasSize(1);
		assertThat(fx.seatHoldId(seatId)).isEqualTo(successes.get(0).holdId());
		assertThat(fx.holdCount(perf.performanceId())).isEqualTo(1);
		assertThat(fx.holdSeatCount(perf.performanceId())).isEqualTo(1);
	}

	@Test
	void 순서가_엇갈린_다중_좌석_요청이_동시에_와도_불변식이_유지된다() throws Exception {
		int rounds = 100;
		int deadlocks = 0;
		int bothFailed = 0;

		for (int r = 0; r < rounds; r++) {
			PerformanceFixture perf = fx.performanceWithSeats(2);
			long a = perf.seat(0);
			long b = perf.seat(1);

			List<Attempt> attempts = runConcurrently(2, i -> () -> attempt("user-" + i, perf.performanceId(),
					i == 0 ? List.of(a, b) : List.of(b, a)));

			deadlocks += (int) attempts.stream().filter(x -> x.outcome() == Outcome.LOCK_CONFLICT).count();
			List<Attempt> successes = successes(attempts);
			assertThat(successes).hasSizeLessThanOrEqualTo(1);
			if (successes.isEmpty()) {
				bothFailed++;
				assertThat(fx.seatStatus(a)).isEqualTo("AVAILABLE");
				assertThat(fx.seatStatus(b)).isEqualTo("AVAILABLE");
				assertThat(fx.holdCount(perf.performanceId())).isZero();
			} else {
				String winner = successes.get(0).holdId();
				assertThat(fx.seatHoldId(a)).isEqualTo(winner);
				assertThat(fx.seatHoldId(b)).isEqualTo(winner);
				assertThat(fx.holdCount(perf.performanceId())).isEqualTo(1);
				assertThat(fx.holdSeatCount(perf.performanceId())).isEqualTo(2);
			}
		}
		System.out.printf("[MEASURE] {a,b}/{b,a} 교차 동시 선점 %d회: LOCK_CONFLICT(데드락·락 대기 초과) %d건, 둘 다 실패 %d회%n",
				rounds, deadlocks, bothFailed);
	}

	@Test
	void 겹치는_다중_좌석_요청_100건이_동시에_와도_부분_선점이_없다() throws Exception {
		PerformanceFixture perf = fx.performanceWithSeats(10);
		Random random = new Random(42);
		List<List<Long>> requests = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			List<Long> shuffled = new ArrayList<>(perf.seatIds());
			Collections.shuffle(shuffled, random);
			requests.add(List.copyOf(shuffled.subList(0, 1 + random.nextInt(4))));
		}

		List<Attempt> attempts = runConcurrently(100,
				i -> () -> attempt("user-" + i, perf.performanceId(), requests.get(i)));

		System.out.println("[MEASURE] 10석에 1~4석 요청 100건 동시: " + count(attempts));

		List<Attempt> successes = successes(attempts);
		assertThat(successes).isNotEmpty();
		// 성공한 선점은 요청한 좌석 전부를 갖고 있다
		for (Attempt s : successes) {
			for (long seatId : s.seatIds()) {
				assertThat(fx.seatHoldId(seatId)).as("seat %d", seatId).isEqualTo(s.holdId());
			}
		}
		// 성공한 선점끼리 좌석이 겹치지 않는다
		List<Long> heldSeats = successes.stream().flatMap(s -> s.seatIds().stream()).toList();
		assertThat(heldSeats).doesNotHaveDuplicates();
		// 실패한 요청은 hold·hold_seat 행을 남기지 않는다
		assertThat(fx.holdCount(perf.performanceId())).isEqualTo(successes.size());
		assertThat(fx.holdSeatCount(perf.performanceId())).isEqualTo(heldSeats.size());
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD'", Integer.class,
				perf.performanceId())).isEqualTo(heldSeats.size());
	}

	// ---------- helpers ----------

	private Attempt attempt(String userId, long performanceId, List<Long> seatIds) {
		try {
			HoldResult result = holdService.hold(userId, performanceId, seatIds);
			return new Attempt(Outcome.SUCCESS, result.holdId(), seatIds);
		} catch (ApiException e) {
			return new Attempt(Outcome.valueOf(e.getCode().name()), null, seatIds);
		} catch (PessimisticLockingFailureException e) {
			return new Attempt(Outcome.LOCK_CONFLICT, null, seatIds);
		}
	}

	/** 모든 작업을 준비시킨 뒤 동시에 출발시킨다. 예상하지 못한 예외는 그대로 테스트 실패로 드러난다. */
	private static <T> List<T> runConcurrently(int n, Function<Integer, Callable<T>> taskFactory)
			throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(n);
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				Callable<T> task = taskFactory.apply(i);
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return task.call();
				}));
			}
			ready.await();
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get(60, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			executor.shutdownNow();
		}
	}

	private static List<Attempt> successes(List<Attempt> attempts) {
		return attempts.stream().filter(a -> a.outcome() == Outcome.SUCCESS).toList();
	}

	private static Map<Outcome, Long> count(List<Attempt> attempts) {
		return attempts.stream().collect(Collectors.groupingBy(Attempt::outcome, Collectors.counting()));
	}
}
