package com.mailplug.seatreservation.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 스키마 검증(ddl-auto validate)용. 쓰기와 조회는 PaymentRepository의 네이티브 쿼리로 한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	@Id
	private String paymentId;

	private String holdId;

	@Enumerated(EnumType.STRING)
	private PaymentStatus status;

	private Instant createdAt;

	private Instant updatedAt;
}
