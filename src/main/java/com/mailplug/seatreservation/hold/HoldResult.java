package com.mailplug.seatreservation.hold;

import java.time.Instant;
import java.util.List;

public record HoldResult(String holdId, long performanceId, List<Long> seatIds, Instant expiresAt) {
}
