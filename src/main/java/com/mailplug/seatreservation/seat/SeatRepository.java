package com.mailplug.seatreservation.seat;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

	/**
	 * 비어 있거나 만료된 좌석만 선점한다. 영향받은 행 수가 요청 좌석 수와 다르면 호출자가 롤백한다 (D-4).
	 * 금지: status·만료 조건 생략 (CHECK가 막지 못하는 유일한 구멍, D-1)
	 */
	@Modifying
	@Query(value = """
			UPDATE seat SET status = 'HELD', hold_id = :holdId,
			  hold_expires_at = (SELECT expires_at FROM hold WHERE id = :holdId)
			WHERE performance_id = :performanceId AND id IN (:seatIds)
			  AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= NOW(6)))
			""", nativeQuery = true)
	int holdSeats(@Param("performanceId") long performanceId, @Param("seatIds") Collection<Long> seatIds,
			@Param("holdId") String holdId);

	/** 선점 취소 시 좌석 해제. hold 취소 UPDATE가 성공한 같은 트랜잭션에서만 호출한다 (D-3). */
	@Modifying
	@Query(value = """
			UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL
			WHERE hold_id = :holdId AND status = 'HELD'
			""", nativeQuery = true)
	int releaseHeld(@Param("holdId") String holdId);

	/**
	 * 승인 전에 선점 좌석을 모두 PK 오름차순으로 잠근다. 경계 시각에 일부만 SOLD가 되는 것을 막고,
	 * 선점 UPDATE(PRIMARY 범위 스캔)와 같은 순서로 잠가 교차 대기를 피한다 (D-8 a).
	 */
	@Query(value = "SELECT id FROM seat WHERE id IN (:seatIds) ORDER BY id FOR UPDATE", nativeQuery = true)
	List<Long> lockByIds(@Param("seatIds") Collection<Long> seatIds);

	/** 유효한 선점의 좌석만 SOLD로. 만료 판정은 이 문장 실행 시점의 NOW(6) (D-8 b). */
	@Modifying
	@Query(value = """
			UPDATE seat SET status = 'SOLD', hold_expires_at = NULL
			WHERE hold_id = :holdId AND status = 'HELD' AND hold_expires_at > NOW(6)
			""", nativeQuery = true)
	int sell(@Param("holdId") String holdId);

	/** 결제 취소로 판매를 되돌린다. SOLD가 바뀌는 유일한 경로 (불변식: 문서화한 결제 취소 규칙). */
	@Modifying
	@Query(value = """
			UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL
			WHERE hold_id = :holdId AND status = 'SOLD'
			""", nativeQuery = true)
	int releaseSold(@Param("holdId") String holdId);

	/** 만료된 HELD는 AVAILABLE로 계산해서 돌려준다 (D-5). */
	@Query(value = """
			SELECT id AS seatId, seat_no AS seatNo,
			  CASE WHEN status = 'HELD' AND hold_expires_at <= NOW(6) THEN 'AVAILABLE' ELSE status END AS status
			FROM seat WHERE performance_id = :performanceId ORDER BY id
			""", nativeQuery = true)
	List<SeatView> findSeatViews(@Param("performanceId") long performanceId);

	interface SeatView {
		Long getSeatId();

		String getSeatNo();

		String getStatus();
	}
}
