package com.mailplug.seatreservation.seat;

import com.mailplug.seatreservation.common.ApiException;
import com.mailplug.seatreservation.common.ErrorCode;
import com.mailplug.seatreservation.performance.PerformanceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SeatQueryService {

	private final PerformanceRepository performanceRepository;
	private final SeatRepository seatRepository;

	@Transactional(readOnly = true)
	public List<SeatResponse> getSeats(long performanceId) {
		if (!performanceRepository.existsById(performanceId)) {
			throw new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND, "performance not found");
		}
		return seatRepository.findSeatViews(performanceId).stream()
				.map(v -> new SeatResponse(v.getSeatId(), v.getSeatNo(), v.getStatus()))
				.toList();
	}
}
