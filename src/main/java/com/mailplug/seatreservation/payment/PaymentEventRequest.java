package com.mailplug.seatreservation.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 결제 이벤트 요청. 길이는 컬럼 길이와 같다.
 * INSERT IGNORE가 길이 초과·CHECK 위반을 0행(중복과 같은 결과)으로 만들므로 여기서 먼저 400으로 거른다 (D-8 0단계).
 */
public record PaymentEventRequest(
		@NotBlank @Size(max = 64) String eventId,
		@NotBlank @Size(max = 64) String paymentId,
		@NotBlank @Size(max = 36) String holdId,
		@NotNull @Pattern(regexp = "approved|cancelled") String status,
		@NotNull Instant occurredAt) {
}
