package com.mailplug.seatreservation.performance;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 조회·스키마 검증(ddl-auto validate)용. 쓰기는 하지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Performance {

	@Id
	private Long id;

	private String title;

	private Instant startsAt;
}
