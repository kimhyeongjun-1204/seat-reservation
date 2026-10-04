package com.mailplug.seatreservation.payment;

import com.mailplug.seatreservation.common.ApiException;
import com.mailplug.seatreservation.common.ErrorCode;
import com.mailplug.seatreservation.common.SqlErrors;
import com.mailplug.seatreservation.hold.HoldRepository;
import com.mailplug.seatreservation.payment.PaymentRepository.PaymentRow;
import com.mailplug.seatreservation.seat.SeatRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 이벤트 처리 (CLAUDE.md D-8). 한 트랜잭션, READ COMMITTED.
 * 순서: 이벤트 기록(INSERT IGNORE) → hold 잠금 → payment 조회·연결 확인 → 전이표 적용 → 결과 기록.
 * 잠금 순서는 hold → seat(PK 오름차순) → payment(쓰기 시점).
 * 만료 판정은 처리 시점의 NOW(6)이고 occurredAt은 기록만 한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentEventService {

	static final String APPROVED_EVENT = "approved";

	private final PaymentEventRepository eventRepository;
	private final PaymentRepository paymentRepository;
	private final HoldRepository holdRepository;
	private final SeatRepository seatRepository;

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PaymentEventResponse handle(PaymentEventCommand command) {
		String eventId = command.eventId();
		String paymentId = command.paymentId();
		String holdId = command.holdId();

		// 1. 중복 판정: PK 충돌이면 0행. 아무것도 바꾸지 않고 현재 커밋된 결제 상태만 돌려준다
		int inserted = eventRepository.insertIgnore(eventId, paymentId, holdId, command.status(),
				toEpochSeconds(command.occurredAt()));
		if (inserted == 0) {
			List<String> status = paymentRepository.findStatus(paymentId);
			return new PaymentEventResponse(eventId, "DUPLICATE", status.isEmpty() ? null : status.get(0));
		}

		// 2. 같은 선점에 대한 이벤트·사용자 취소를 한 줄로 세운다. 없으면 롤백(이벤트 기록도 사라짐)
		if (holdRepository.lockById(holdId).isEmpty()) {
			throw new ApiException(ErrorCode.HOLD_NOT_FOUND, "hold not found");
		}

		// 3. 결제 하나 ↔ 선점 하나. 기존 연결과 다른 조합이면 409
		PaymentStatus current = null;
		for (PaymentRow row : paymentRepository.findLinked(paymentId, holdId)) {
			if (!row.getPaymentId().equals(paymentId) || !row.getHoldId().equals(holdId)) {
				throw new ApiException(ErrorCode.PAYMENT_CONFLICT, "payment is linked to another hold");
			}
			current = PaymentStatus.valueOf(row.getStatus());
		}

		// 4. 전이표
		boolean approved = APPROVED_EVENT.equals(command.status());
		PaymentStatus next;
		String result;
		if (current == null) {
			next = approved ? approve(holdId) : cancelUnpaidHold(holdId);
			insertPayment(paymentId, holdId, next);
			result = "APPLIED";
		} else if (!approved && current == PaymentStatus.APPROVED) {
			refundSold(holdId);
			next = changePayment(paymentId, current, PaymentStatus.CANCELLED);
			result = "APPLIED";
		} else if (!approved && current == PaymentStatus.REFUND_REQUIRED) {
			// 환불 완료로 간주. 좌석은 이미 이 선점의 것이 아니다
			next = changePayment(paymentId, current, PaymentStatus.CANCELLED);
			result = "APPLIED";
		} else {
			// CANCELLED + 모든 이벤트, APPROVED·REFUND_REQUIRED + approved
			next = current;
			result = "IGNORED";
		}
		eventRepository.updateResult(eventId, result);
		return new PaymentEventResponse(eventId, result, next.name());
	}

	/** 좌석을 먼저 모두 잠근 뒤 유효한 좌석만 SOLD로. 전부면 APPROVED, 하나도 없으면 REFUND_REQUIRED. */
	private PaymentStatus approve(String holdId) {
		List<Long> seatIds = holdRepository.findSeatIds(holdId);
		if (!seatIds.isEmpty()) {
			seatRepository.lockByIds(seatIds);
		}
		int sold = seatRepository.sell(holdId);
		if (sold == 0) {
			// 만료·취소된 선점: 좌석과 hold는 그대로 두고 환불 대상으로 기록 (D-10)
			return PaymentStatus.REFUND_REQUIRED;
		}
		if (sold != seatIds.size() || holdRepository.confirm(holdId) != 1) {
			// 좌석을 먼저 잠갔으므로 일부만 판매되는 경우는 불변식 위반이다 → 롤백
			throw new IllegalStateException(
					"partial confirm: hold=%s sold=%d seats=%d".formatted(holdId, sold, seatIds.size()));
		}
		return PaymentStatus.APPROVED;
	}

	/** 승인 전에 취소가 먼저 온 경우. 선점이 아직 유효하면 해제한다 (D-3과 같은 hold → seat 순서). */
	private PaymentStatus cancelUnpaidHold(String holdId) {
		if (holdRepository.cancelActiveByPayment(holdId) == 1) {
			seatRepository.releaseHeld(holdId);
		}
		return PaymentStatus.CANCELLED;
	}

	/** 승인 후 취소: 판매한 좌석을 되돌리고 확정을 취소한다. */
	private void refundSold(String holdId) {
		seatRepository.releaseSold(holdId);
		holdRepository.cancelConfirmed(holdId);
	}

	private void insertPayment(String paymentId, String holdId, PaymentStatus status) {
		try {
			paymentRepository.insert(paymentId, holdId, status.name());
		} catch (DataIntegrityViolationException e) {
			// 같은 paymentId가 다른 선점으로, 또는 같은 선점이 다른 paymentId로 동시에 연결된 경우
			if (SqlErrors.errorCode(e) == SqlErrors.ER_DUP_ENTRY) {
				throw new ApiException(ErrorCode.PAYMENT_CONFLICT, "payment is linked to another hold");
			}
			throw e;
		}
	}

	private PaymentStatus changePayment(String paymentId, PaymentStatus expected, PaymentStatus next) {
		if (paymentRepository.updateStatus(paymentId, expected.name(), next.name()) != 1) {
			// hold 잠금 아래에서 읽은 값이라 바뀔 수 없다. 바뀌었다면 잠금 순서 위반 → 롤백
			throw new IllegalStateException("payment status changed concurrently: " + paymentId);
		}
		return next;
	}

	/** Instant → epoch 초(소수 6자리). FROM_UNIXTIME에 넘긴다 (double 거치지 않음). */
	static BigDecimal toEpochSeconds(Instant instant) {
		return BigDecimal.valueOf(instant.getEpochSecond())
				.add(BigDecimal.valueOf(instant.getNano() / 1_000, 6));
	}
}
