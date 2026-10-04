package com.mailplug.seatreservation.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 스키마 검증(ddl-auto validate)용. 쓰기는 PaymentRepository의 네이티브 쿼리로 한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentEvent {

	@Id
	private String eventId;

	private String paymentId;

	private String holdId;

	/** 결제 제공자가 보낸 값 그대로(approved / cancelled). */
	private String status;

	private Instant occurredAt;

	private Instant receivedAt;

	private String result;
}
