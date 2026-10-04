package com.mailplug.seatreservation.hold;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** seat.hold.ttl: 선점 유지 시간. 만료 시각 계산은 DB(NOW(6))가 하고 여기서는 길이만 준다. */
@ConfigurationProperties("seat.hold")
public record HoldProperties(@DefaultValue("5m") Duration ttl) {
}
