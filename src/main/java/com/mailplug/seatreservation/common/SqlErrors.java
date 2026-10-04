package com.mailplug.seatreservation.common;

import java.sql.SQLException;

/** 예외 원인 사슬에서 MariaDB 오류 번호를 꺼낸다. */
public final class SqlErrors {

	public static final int ER_DUP_ENTRY = 1062;
	public static final int ER_NO_REFERENCED_ROW = 1452;

	private SqlErrors() {
	}

	public static int errorCode(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				return sql.getErrorCode();
			}
		}
		return -1;
	}
}
