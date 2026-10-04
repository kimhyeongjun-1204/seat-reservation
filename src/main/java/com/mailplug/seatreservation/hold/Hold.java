package com.mailplug.seatreservation.hold;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 조회·스키마 검증(ddl-auto validate)용. 쓰기는 모두 HoldRepository의 네이티브 쿼리로 한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Hold {

	@Id
	private String id;

	private Long performanceId;

	private String userId;

	@Enumerated(EnumType.STRING)
	private HoldStatus status;

	private Instant expiresAt;

	private Instant createdAt;
}
