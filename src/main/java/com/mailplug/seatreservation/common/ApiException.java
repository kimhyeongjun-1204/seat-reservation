package com.mailplug.seatreservation.common;

public class ApiException extends RuntimeException {

	private final ErrorCode code;

	public ApiException(ErrorCode code, String detail) {
		super(detail);
		this.code = code;
	}

	public ErrorCode getCode() {
		return code;
	}
}
