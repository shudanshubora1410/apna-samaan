-- =============================================================================
-- V4: Payments & Idempotent Webhook Event Ledger (Phase 4)
-- Stores Razorpay Test Mode orders, HMAC-verified captures, pre-pickup/timeout
-- refunds, and deduplicated webhook deliveries.
-- =============================================================================

CREATE TABLE payments (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    shipment_id             BIGINT          NOT NULL,
    customer_user_id        BIGINT          NOT NULL,

    razorpay_order_id       VARCHAR(64)     NOT NULL,
    razorpay_payment_id     VARCHAR(64)     NULL,
    razorpay_signature      VARCHAR(128)    NULL,

    amount_inr              DECIMAL(10, 2)  NOT NULL,
    amount_paise            BIGINT          NOT NULL,
    currency                VARCHAR(10)     NOT NULL DEFAULT 'INR',
    receipt                 VARCHAR(40)     NOT NULL,

    status                  VARCHAR(30)     NOT NULL,
    failure_reason          VARCHAR(300)    NULL,

    razorpay_refund_id      VARCHAR(64)     NULL,
    refunded_amount_inr     DECIMAL(10, 2)  NULL,
    refund_reason           VARCHAR(300)    NULL,

    captured_at             DATETIME(6)     NULL,
    refunded_at             DATETIME(6)     NULL,

    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              DATETIME(6)     NOT NULL,
    updated_at              DATETIME(6)     NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_payments_razorpay_order_id UNIQUE (razorpay_order_id),
    CONSTRAINT uk_payments_razorpay_payment_id UNIQUE (razorpay_payment_id),
    CONSTRAINT uk_payments_razorpay_refund_id UNIQUE (razorpay_refund_id),
    CONSTRAINT fk_payments_shipment
        FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE CASCADE,
    CONSTRAINT fk_payments_customer_user
        FOREIGN KEY (customer_user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_payments_shipment_created (shipment_id, created_at),
    INDEX idx_payments_customer_status (customer_user_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE payment_webhook_events (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    event_key               VARCHAR(80)     NOT NULL,
    event_type              VARCHAR(60)     NOT NULL,
    razorpay_order_id       VARCHAR(64)     NULL,
    razorpay_payment_id     VARCHAR(64)     NULL,
    processed_at            DATETIME(6)     NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_payment_webhook_event_key UNIQUE (event_key),
    INDEX idx_payment_webhook_order (razorpay_order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
