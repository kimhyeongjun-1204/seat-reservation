package com.mailplug.seatreservation.hold;

import com.mailplug.seatreservation.common.ApiException;
import com.mailplug.seatreservation.common.ErrorCode;
import com.mailplug.seatreservation.seat.SeatRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HoldService {

	private static final int ER_NO_REFERENCED_ROW = 1452;

	private final HoldRepository holdRepository;
	private final SeatRepository seatRepository;
	private final HoldProperties properties;

	/**
	 * 한 트랜잭션: hold INSERT → seat 다중 UPDATE 한 문장 → hold_seat INSERT (D-4).
	 * 요청 검증(1~10석, 중복 없음)은 호출 전에 끝나 있어야 한다.
	 */
	@Transactional
	public HoldResult hold(String userId, long performanceId, List<Long> seatIds) {
		String holdId = UUID.randomUUID().toString();

		try {
			holdRepository.insertActive(holdId, performanceId, userId, properties.ttl().toSeconds());
		} catch (DataIntegrityViolationException e) {
			// 404 변환은 hold INSERT의 FK 위반(performance_id)에만 적용한다. 다른 단계의 1452는 서버 오류로 둔다
			if (sqlErrorCode(e) == ER_NO_REFERENCED_ROW) {
				throw new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND, "performance not found");
			}
			throw e;
		}

		int held = seatRepository.holdSeats(performanceId, seatIds, holdId);
		if (held != seatIds.size()) {
			// 어느 좌석이 원인인지는 응답하지 않는다. 없는 좌석·다른 공연 좌석도 같은 409 (D-4)
			throw new ApiException(ErrorCode.SEAT_UNAVAILABLE, "some seats are not available");
		}
		for (long seatId : seatIds) { // 최대 10석 (D-7)
			holdRepository.insertHoldSeat(holdId, seatId);
		}

		// 쓰기를 모두 마친 뒤 DB가 계산한 만료 시각을 읽는다 (앱 시계 사용 안 함)
		Instant expiresAt = toInstant(holdRepository.findExpiresAtEpochSeconds(holdId));
		return new HoldResult(holdId, performanceId, seatIds, expiresAt);
	}

	/** hold → seat 순서로, 한 트랜잭션에서 취소한다 (D-3). */
	@Transactional
	public void cancel(String userId, String holdId) {
		if (holdRepository.cancelActive(holdId, userId) == 0) {
			throw new ApiException(ErrorCode.HOLD_NOT_FOUND, "hold not found");
		}
		seatRepository.releaseHeld(holdId);
	}

	/** UNIX_TIMESTAMP(TIMESTAMP(6)) 값을 초·마이크로초로 나눠 Instant로 바꾼다 (double 거치지 않음). */
	static Instant toInstant(BigDecimal epochSeconds) {
		BigDecimal[] parts = epochSeconds.divideAndRemainder(BigDecimal.ONE);
		long seconds = parts[0].longValueExact();
		long micros = parts[1].movePointRight(6).longValueExact();
		return Instant.ofEpochSecond(seconds, micros * 1_000);
	}

	private static int sqlErrorCode(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				return sql.getErrorCode();
			}
		}
		return -1;
	}
}
