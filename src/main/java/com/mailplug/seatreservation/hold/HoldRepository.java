package com.mailplug.seatreservation.hold;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldRepository extends JpaRepository<Hold, String> {

	/** 만료 시각은 여기서 DB 시각으로 한 번만 계산한다 (D-4). save()는 쓰지 않는다. */
	@Modifying
	@Query(value = """
			INSERT INTO hold (id, performance_id, user_id, status, expires_at)
			VALUES (:id, :performanceId, :userId, 'ACTIVE', NOW(6) + INTERVAL :ttlSeconds SECOND)
			""", nativeQuery = true)
	int insertActive(@Param("id") String id, @Param("performanceId") long performanceId,
			@Param("userId") String userId, @Param("ttlSeconds") long ttlSeconds);

	/**
	 * 선점한 좌석을 기록한다. seat UPDATE의 행 수 검사를 통과한 뒤에만 호출한다.
	 * INSERT … SELECT FROM seat는 RR에서 locking read라 idx_seat_hold_id에 gap 락을 걸므로 쓰지 않는다.
	 */
	@Modifying
	@Query(value = "INSERT INTO hold_seat (hold_id, seat_id) VALUES (:holdId, :seatId)", nativeQuery = true)
	int insertHoldSeat(@Param("holdId") String holdId, @Param("seatId") long seatId);

	/**
	 * 만료 시각을 세션 시간대와 무관한 epoch 초(소수 6자리)로 읽는다.
	 * 엔티티의 Instant는 세션 시간대가 UTC일 때만 정확하므로 응답용 시각은 이 방법으로만 읽는다 (D-7).
	 */
	@Query(value = "SELECT UNIX_TIMESTAMP(expires_at) FROM hold WHERE id = :holdId", nativeQuery = true)
	BigDecimal findExpiresAtEpochSeconds(@Param("holdId") String holdId);

	/** 결제 이벤트 처리의 첫 잠금. 같은 선점에 대한 이벤트·취소를 한 줄로 세운다 (잠금 순서 hold → seat → payment). */
	@Query(value = "SELECT id FROM hold WHERE id = :holdId FOR UPDATE", nativeQuery = true)
	List<String> lockById(@Param("holdId") String holdId);

	/** 승인 시 확정할 좌석 목록. 선점 후 바뀌지 않는 기록이라 잠그지 않고 읽는다. */
	@Query(value = "SELECT seat_id FROM hold_seat WHERE hold_id = :holdId ORDER BY seat_id", nativeQuery = true)
	List<Long> findSeatIds(@Param("holdId") String holdId);

	/** 결제 승인으로 확정. 좌석 확정 UPDATE가 선점 좌석 수와 같을 때만 호출한다 (D-8 c). */
	@Modifying
	@Query(value = "UPDATE hold SET status = 'CONFIRMED' WHERE id = :holdId AND status = 'ACTIVE'", nativeQuery = true)
	int confirm(@Param("holdId") String holdId);

	/** 결제 취소로 유효한 선점을 해제 (사용자 확인 없음, D-8 전이표 "없음 + cancelled"). */
	@Modifying
	@Query(value = """
			UPDATE hold SET status = 'CANCELLED'
			WHERE id = :holdId AND status = 'ACTIVE' AND expires_at > NOW(6)
			""", nativeQuery = true)
	int cancelActiveByPayment(@Param("holdId") String holdId);

	/** 결제 취소로 확정을 되돌림 (D-8 전이표 "APPROVED + cancelled"). */
	@Modifying
	@Query(value = "UPDATE hold SET status = 'CANCELLED' WHERE id = :holdId AND status = 'CONFIRMED'",
			nativeQuery = true)
	int cancelConfirmed(@Param("holdId") String holdId);

	/** 주인·유효 여부를 한 문장에서 판정한다. 0행이면 원인과 관계없이 404 (D-3). */
	@Modifying
	@Query(value = """
			UPDATE hold SET status = 'CANCELLED'
			WHERE id = :holdId AND user_id = :userId AND status = 'ACTIVE' AND expires_at > NOW(6)
			""", nativeQuery = true)
	int cancelActive(@Param("holdId") String holdId, @Param("userId") String userId);
}
