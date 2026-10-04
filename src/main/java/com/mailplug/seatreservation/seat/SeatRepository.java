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
