package com.mailplug.seatreservation.common;

import org.springframework.http.HttpStatus;

/** 오류 응답의 code 필드 (D-7). */
public enum ErrorCode {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST),
	PERFORMANCE_NOT_FOUND(HttpStatus.NOT_FOUND),
	HOLD_NOT_FOUND(HttpStatus.NOT_FOUND),
	SEAT_UNAVAILABLE(HttpStatus.CONFLICT),
	LOCK_CONFLICT(HttpStatus.CONFLICT),
	PAYMENT_CONFLICT(HttpStatus.CONFLICT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
