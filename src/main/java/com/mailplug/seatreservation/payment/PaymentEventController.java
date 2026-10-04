package com.mailplug.seatreservation.payment;

import com.mailplug.seatreservation.common.ApiException;
import com.mailplug.seatreservation.common.ErrorCode;
import jakarta.validation.Valid;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentEventController {

	/** occurred_at은 TIMESTAMP(6)이라 이 범위 밖의 값은 저장할 수 없다 (MariaDB 11.4 기준). */
	static final Instant TIMESTAMP_MIN = Instant.parse("1970-01-01T00:00:01Z");
	static final Instant TIMESTAMP_MAX = Instant.parse("2038-01-19T03:14:07.999999Z");

	private final PaymentEventService paymentEventService;

	@PostMapping("/payment-events")
	public PaymentEventResponse receive(@RequestBody @Valid PaymentEventRequest request) {
		if (request.occurredAt().isBefore(TIMESTAMP_MIN) || request.occurredAt().isAfter(TIMESTAMP_MAX)) {
			throw new ApiException(ErrorCode.INVALID_REQUEST, "occurredAt is out of range");
		}
		try {
			return paymentEventService.handle(new PaymentEventCommand(request.eventId(), request.paymentId(),
					request.holdId(), request.status(), request.occurredAt()));
		} catch (PessimisticLockingFailureException e) {
			// 데드락·락 대기 초과: 트랜잭션은 이미 롤백됐고 이벤트 기록도 사라졌다. 재전송을 받기 위해 503 (서버 재시도 없음)
			throw new ApiException(ErrorCode.LOCK_CONFLICT, "lock conflict, retry later", HttpStatus.SERVICE_UNAVAILABLE);
		}
	}
}
