package com.mailplug.seatreservation.payment;

/** CANCELLED는 종료 상태: 이후 어떤 이벤트도 상태를 바꾸지 않는다. */
public enum PaymentStatus {
	APPROVED, REFUND_REQUIRED, CANCELLED
}
