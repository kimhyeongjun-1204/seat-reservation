-- 시각 컬럼은 모두 TIMESTAMP(6): UTC로 저장되므로 세션 시간대가 달라도 NOW(6) 비교 결과가 같다 (D-6)

CREATE TABLE performance (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    title     VARCHAR(200) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

-- 선점 기록. 만료는 status가 아니라 expires_at <= NOW(6)로 판정한다 (D-5)
CREATE TABLE hold (
    id             VARCHAR(36)  NOT NULL,
    performance_id BIGINT       NOT NULL,
    user_id        VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    expires_at     TIMESTAMP(6) NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_hold_performance FOREIGN KEY (performance_id) REFERENCES performance (id),
    CONSTRAINT ck_hold_status CHECK (status IN ('ACTIVE', 'CANCELLED', 'CONFIRMED'))
);

-- 점유 판정은 이 테이블만 본다 (D-1)
CREATE TABLE seat (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    performance_id  BIGINT       NOT NULL,
    seat_no         VARCHAR(10)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE',
    hold_id         VARCHAR(36)  NULL,
    hold_expires_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_seat_performance_seat_no UNIQUE (performance_id, seat_no),
    INDEX idx_seat_hold_id (hold_id),
    CONSTRAINT fk_seat_performance FOREIGN KEY (performance_id) REFERENCES performance (id),
    CONSTRAINT fk_seat_hold FOREIGN KEY (hold_id) REFERENCES hold (id),
    CONSTRAINT ck_seat_state CHECK (
        (status = 'AVAILABLE' AND hold_id IS NULL     AND hold_expires_at IS NULL)
     OR (status = 'HELD'      AND hold_id IS NOT NULL AND hold_expires_at IS NOT NULL)
     OR (status = 'SOLD'      AND hold_id IS NOT NULL AND hold_expires_at IS NULL)
    )
);

CREATE TABLE hold_seat (
    hold_id VARCHAR(36) NOT NULL,
    seat_id BIGINT      NOT NULL,
    PRIMARY KEY (hold_id, seat_id),
    INDEX idx_hold_seat_seat_id (seat_id),
    CONSTRAINT fk_hold_seat_hold FOREIGN KEY (hold_id) REFERENCES hold (id),
    CONSTRAINT fk_hold_seat_seat FOREIGN KEY (seat_id) REFERENCES seat (id)
);

-- 결제 하나 ↔ 선점 하나 (payment_id PK, hold_id UNIQUE) (D-8)
CREATE TABLE payment (
    payment_id VARCHAR(64)  NOT NULL,
    hold_id    VARCHAR(36)  NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (payment_id),
    CONSTRAINT uk_payment_hold_id UNIQUE (hold_id),
    CONSTRAINT fk_payment_hold FOREIGN KEY (hold_id) REFERENCES hold (id),
    CONSTRAINT ck_payment_status CHECK (status IN ('APPROVED', 'REFUND_REQUIRED', 'CANCELLED'))
);

-- 수신 이벤트 기록. 처리 전에 먼저 INSERT하므로 payment·hold FK는 두지 않는다 (D-8)
-- result: INSERT 시점엔 NULL, 같은 트랜잭션 마지막에 APPLIED/IGNORED. DUPLICATE는 행이 생기지 않아 저장되지 않음
CREATE TABLE payment_event (
    event_id    VARCHAR(64)  NOT NULL,
    payment_id  VARCHAR(64)  NOT NULL,
    hold_id     VARCHAR(36)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    result      VARCHAR(16)  NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT ck_payment_event_status CHECK (status IN ('approved', 'cancelled')),
    CONSTRAINT ck_payment_event_result CHECK (result IS NULL OR result IN ('APPLIED', 'IGNORED'))
);
