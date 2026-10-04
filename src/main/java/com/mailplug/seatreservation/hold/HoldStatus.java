package com.mailplug.seatreservation.hold;

/** 만료는 상태값이 아니라 expires_at <= NOW(6)로 판정한다 (D-5). */
public enum HoldStatus {
	ACTIVE, CANCELLED, CONFIRMED
}
