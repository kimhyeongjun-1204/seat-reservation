package com.mailplug.seatreservation.hold;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateHoldRequest(
		@NotNull Long performanceId,
		@NotNull @Size(min = 1, max = 10) List<@NotNull Long> seatIds) {
}
