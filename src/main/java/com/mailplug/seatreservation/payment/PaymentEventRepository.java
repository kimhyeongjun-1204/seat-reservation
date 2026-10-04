package com.mailplug.seatreservation.payment;

import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, String> {

	/**
	 * 이벤트를 먼저 기록한다. 0행이면 같은 eventId가 이미 있다는 뜻(중복)이다.
	 * INSERT IGNORE는 CHECK 위반·길이 초과도 0행으로 만들므로 입력은 호출 전에 검증돼 있어야 한다 (D-8 0단계).
	 * occurred_at은 epoch 초로 넘겨 FROM_UNIXTIME으로 넣는다: Instant를 그대로 바인딩하면 세션 시간대에 따라 어긋날 수 있다 (D-12).
	 */
	@Modifying
	@Query(value = """
			INSERT IGNORE INTO payment_event (event_id, payment_id, hold_id, status, occurred_at)
			VALUES (:eventId, :paymentId, :holdId, :status, FROM_UNIXTIME(:occurredAtEpoch))
			""", nativeQuery = true)
	int insertIgnore(@Param("eventId") String eventId, @Param("paymentId") String paymentId,
			@Param("holdId") String holdId, @Param("status") String status,
			@Param("occurredAtEpoch") BigDecimal occurredAtEpoch);

	@Modifying
	@Query(value = "UPDATE payment_event SET result = :result WHERE event_id = :eventId", nativeQuery = true)
	int updateResult(@Param("eventId") String eventId, @Param("result") String result);
}
