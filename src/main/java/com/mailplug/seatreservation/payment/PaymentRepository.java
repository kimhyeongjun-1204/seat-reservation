package com.mailplug.seatreservation.payment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * payment는 FOR UPDATE로 읽지 않는다. 첫 이벤트 시점엔 행이 없어 갭 락이 걸리고 서로 다른 결제의 첫 이벤트끼리 데드락이 날 수 있다.
 * 대신 결제 트랜잭션을 READ COMMITTED로 실행해 일반 SELECT도 최신 커밋 값을 읽는다.
 * 같은 선점의 이벤트는 hold 행 잠금으로 이미 한 줄로 선다.
 */
public interface PaymentRepository extends JpaRepository<Payment, String> {

	@Query(value = """
			SELECT payment_id AS paymentId, hold_id AS holdId, status AS status
			FROM payment WHERE payment_id = :paymentId OR hold_id = :holdId
			""", nativeQuery = true)
	List<PaymentRow> findLinked(@Param("paymentId") String paymentId, @Param("holdId") String holdId);

	@Query(value = "SELECT status FROM payment WHERE payment_id = :paymentId", nativeQuery = true)
	List<String> findStatus(@Param("paymentId") String paymentId);

	@Modifying
	@Query(value = "INSERT INTO payment (payment_id, hold_id, status) VALUES (:paymentId, :holdId, :status)",
			nativeQuery = true)
	int insert(@Param("paymentId") String paymentId, @Param("holdId") String holdId, @Param("status") String status);

	@Modifying
	@Query(value = """
			UPDATE payment SET status = :status, updated_at = CURRENT_TIMESTAMP(6)
			WHERE payment_id = :paymentId AND status = :expected
			""", nativeQuery = true)
	int updateStatus(@Param("paymentId") String paymentId, @Param("expected") String expected,
			@Param("status") String status);

	interface PaymentRow {
		String getPaymentId();

		String getHoldId();

		String getStatus();
	}
}
