import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 과제의 부하 시나리오: 공연 1개(좌석 1,000석)에 동시 200개 요청으로 좌석 선점 POST /holds 총 5,000건.
 * 요청마다 좌석 1~4개, 요청의 60%는 인기 구간(A열, 좌석 id 1~100)을 노린다.
 *
 * 실행: java load-test/LoadTest.java [baseUrl] [totalRequests] [concurrency] [hotRatio]
 * 기본값: http://localhost:8080 5000 200 0.6
 *
 * 측정 방식: 요청을 보내기 직전부터 응답 본문을 다 받을 때까지의 시간(클라이언트 관점 지연)을 요청마다 기록한다.
 * 처리량 = 완료 요청 수 / 첫 요청 시작부터 마지막 응답까지의 경과 시간.
 */
public class LoadTest {

	static final long PERFORMANCE_ID = 1;
	static final int SEAT_COUNT = 1000;
	static final int HOT_SEATS = 100; // A열: id 1~100

	public static void main(String[] args) throws Exception {
		String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
		int total = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
		int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 200;
		double hotRatio = args.length > 3 ? Double.parseDouble(args[3]) : 0.6;

		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(10))
				.build();

		// 워밍업: JIT·커넥션 준비용 조회 요청 (측정에서 제외)
		for (int i = 0; i < 50; i++) {
			client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/performances/1/seats")).GET().build(),
					HttpResponse.BodyHandlers.discarding());
		}

		long[] latencyNanos = new long[total];
		String[] outcome = new String[total];
		boolean[] hot = new boolean[total];
		AtomicInteger next = new AtomicInteger();
		LongAdder seatsRequested = new LongAdder();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(concurrency);

		List<Thread> workers = new ArrayList<>();
		for (int w = 0; w < concurrency; w++) {
			Thread t = Thread.ofPlatform().start(() -> {
				try {
					start.await();
					int i;
					while ((i = next.getAndIncrement()) < total) {
						ThreadLocalRandom r = ThreadLocalRandom.current();
						boolean isHot = r.nextDouble() < hotRatio;
						int count = 1 + r.nextInt(4);
						Set<Integer> seats = new LinkedHashSet<>();
						while (seats.size() < count) {
							seats.add(isHot ? 1 + r.nextInt(HOT_SEATS) : HOT_SEATS + 1 + r.nextInt(SEAT_COUNT - HOT_SEATS));
						}
						seatsRequested.add(count);
						String body = "{\"performanceId\":%d,\"seatIds\":%s}".formatted(PERFORMANCE_ID,
								Arrays.toString(seats.toArray()));
						HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/holds"))
								.header("Content-Type", "application/json")
								.header("X-User-Id", "load-user-" + i)
								.timeout(Duration.ofSeconds(60))
								.POST(HttpRequest.BodyPublishers.ofString(body))
								.build();
						long t0 = System.nanoTime();
						String result;
						try {
							HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
							result = res.statusCode() + codeOf(res.body());
						} catch (Exception e) {
							result = "EXCEPTION " + e.getClass().getSimpleName();
						}
						latencyNanos[i] = System.nanoTime() - t0;
						outcome[i] = result;
						hot[i] = isHot;
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
			workers.add(t);
		}

		long begin = System.nanoTime();
		start.countDown();
		done.await();
		long elapsed = System.nanoTime() - begin;

		Map<String, Integer> byOutcome = new TreeMap<>();
		Map<String, Integer> hotOutcome = new TreeMap<>();
		int hotCount = 0;
		for (int i = 0; i < total; i++) {
			byOutcome.merge(outcome[i], 1, Integer::sum);
			if (hot[i]) {
				hotCount++;
				hotOutcome.merge(outcome[i], 1, Integer::sum);
			}
		}
		long[] sorted = latencyNanos.clone();
		Arrays.sort(sorted);

		System.out.printf("요청 %d건, 동시 %d, 인기 구간 비율 설정 %.0f%% (실제 %d건 = %.1f%%), 요청 좌석 합계 %d석%n",
				total, concurrency, hotRatio * 100, hotCount, hotCount * 100.0 / total, seatsRequested.sum());
		System.out.printf("경과 %.2f초, 처리량 %.1f 요청/초%n", elapsed / 1e9, total / (elapsed / 1e9));
		System.out.printf("지연(ms) 평균 %.1f / p50 %.1f / p95 %.1f / p99 %.1f / 최대 %.1f%n",
				Arrays.stream(sorted).average().orElse(0) / 1e6, pct(sorted, 50), pct(sorted, 95), pct(sorted, 99),
				sorted[total - 1] / 1e6);
		System.out.println("결과별 건수: " + byOutcome);
		System.out.println("인기 구간 요청 결과: " + hotOutcome);
	}

	static double pct(long[] sorted, int p) {
		int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
		return sorted[Math.max(0, idx)] / 1e6;
	}

	/** 응답 본문에서 오류 code만 뽑는다 (성공 응답에는 없음). */
	static String codeOf(String body) {
		int i = body.indexOf("\"code\":\"");
		if (i < 0) {
			return "";
		}
		int s = i + 8;
		return " " + body.substring(s, body.indexOf('"', s));
	}
}
