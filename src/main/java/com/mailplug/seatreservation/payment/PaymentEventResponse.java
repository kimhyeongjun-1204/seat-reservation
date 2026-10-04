package com.mailplug.seatreservation.payment;

/** result: APPLIED / IGNORED / DUPLICATE. paymentStatus: 처리 후 결제 상태 (DUPLICATE면 현재 커밋된 상태, 없으면 null). */
public record PaymentEventResponse(String eventId, String result, String paymentStatus) {
}
