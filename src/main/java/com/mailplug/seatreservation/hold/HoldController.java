package com.mailplug.seatreservation.hold;

import com.mailplug.seatreservation.common.ApiException;
import com.mailplug.seatreservation.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HoldController {

	static final String USER_ID_HEADER = "X-User-Id";

	private final HoldService holdService;

	@PostMapping("/holds")
	@ResponseStatus(HttpStatus.CREATED)
	public HoldResult hold(@RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 64) String userId,
			@RequestBody @Valid CreateHoldRequest request) {
		// 중복 좌석은 DB 접근 전에 거절한다 (행 수 비교가 어긋나므로, D-4)
		if (new HashSet<>(request.seatIds()).size() != request.seatIds().size()) {
			throw new ApiException(ErrorCode.INVALID_REQUEST, "seatIds must not contain duplicates");
		}
		return holdService.hold(userId, request.performanceId(), request.seatIds());
	}

	@DeleteMapping("/holds/{holdId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cancel(@RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 64) String userId,
			@PathVariable String holdId) {
		holdService.cancel(userId, holdId);
	}
}
