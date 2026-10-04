package com.mailplug.seatreservation.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

	private final ErrorCode code;
	private final HttpStatus status;

	public ApiException(ErrorCode code, String detail) {
		this(code, detail, code.status());
	}

	/** 같은 code라도 경로에 따라 상태가 다를 때 쓴다 (예: 결제 이벤트의 LOCK_CONFLICT는 503). */
	public ApiException(ErrorCode code, String detail, HttpStatus status) {
		super(detail);
		this.code = code;
		this.status = status;
	}

	public ErrorCode getCode() {
		return code;
	}

	public HttpStatus getStatus() {
		return status;
	}
}
