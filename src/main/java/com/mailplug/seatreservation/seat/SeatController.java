package com.mailplug.seatreservation.seat;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SeatController {

	private final SeatQueryService seatQueryService;

	@GetMapping("/performances/{performanceId}/seats")
	public List<SeatResponse> getSeats(@PathVariable long performanceId) {
		return seatQueryService.getSeats(performanceId);
	}
}
