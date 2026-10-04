package com.mailplug.seatreservation.common;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 모든 오류를 ProblemDetail(RFC 9457) + code 필드로 응답한다 (D-7). */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ProblemDetail handleApi(ApiException e) {
		return problem(e.getCode(), e.getMessage());
	}

	/** 데드락(1213)·락 대기 초과(1205)는 재시도 없이 409 (D-4). */
	@ExceptionHandler(PessimisticLockingFailureException.class)
	ProblemDetail handleLock(PessimisticLockingFailureException e) {
		return problem(ErrorCode.LOCK_CONFLICT, "lock conflict, retry later");
	}

	/**
	 * 프레임워크가 만드는 400(검증 실패, 헤더 누락, 본문 파싱 실패 등)에도 code를 붙인다.
	 * ProblemDetail은 super 안에서 만들어지므로(body 인자는 null) 결과 본문에 붙인다.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail pd
				&& statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
			pd.setProperty("code", ErrorCode.INVALID_REQUEST.name());
		}
		return response;
	}

	private static ProblemDetail problem(ErrorCode code, String detail) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
		pd.setProperty("code", code.name());
		return pd;
	}
}
