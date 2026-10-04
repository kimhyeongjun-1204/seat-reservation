package com.mailplug.seatreservation.payment;

import java.time.Instant;

/** 검증을 마친 결제 이벤트. status는 "approved" 또는 "cancelled". */
public record PaymentEventCommand(String eventId, String paymentId, String holdId, String status,
		Instant occurredAt) {
}
