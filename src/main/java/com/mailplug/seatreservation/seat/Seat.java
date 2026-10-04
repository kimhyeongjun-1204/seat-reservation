package com.mailplug.seatreservation.seat;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 조회·스키마 검증(ddl-auto validate)용. 쓰기는 모두 SeatRepository의 조건부 네이티브 UPDATE로 한다.
 * 같은 트랜잭션에서 이 엔티티를 쓰기 전에 조회하지 않는다 (D-4).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

	@Id
	private Long id;

	private Long performanceId;

	private String seatNo;

	@Enumerated(EnumType.STRING)
	private SeatStatus status;

	private String holdId;

	private Instant holdExpiresAt;
}
