-- =============================================================================
-- V3: Shipments & Shipment Status Audit History (Phase 3)
-- Supports deterministic server-side INR quoting/booking, centralized status
-- transitions, customer pre-pickup cancellation, and Phase 4/5 payment/matching.
-- =============================================================================

CREATE TABLE shipments (
    id                          BIGINT          NOT NULL AUTO_INCREMENT,
    tracking_code               VARCHAR(24)     NOT NULL,
    customer_user_id            BIGINT          NOT NULL,
    assigned_driver_profile_id  BIGINT          NULL,
    vehicle_type_id             BIGINT          NOT NULL,

    pickup_address              VARCHAR(300)    NOT NULL,
    pickup_city                 VARCHAR(80)     NOT NULL,
    pickup_lat                  DOUBLE          NOT NULL,
    pickup_lng                  DOUBLE          NOT NULL,
    pickup_contact_name         VARCHAR(100)    NOT NULL,
    pickup_contact_phone        VARCHAR(15)     NOT NULL,

    drop_address                VARCHAR(300)    NOT NULL,
    drop_city                   VARCHAR(80)     NOT NULL,
    drop_lat                    DOUBLE          NOT NULL,
    drop_lng                    DOUBLE          NOT NULL,
    drop_contact_name           VARCHAR(100)    NOT NULL,
    drop_contact_phone          VARCHAR(15)     NOT NULL,

    package_description         VARCHAR(255)    NOT NULL,
    package_category            VARCHAR(40)     NOT NULL,
    weight_kg                   DECIMAL(10, 2)  NOT NULL,
    length_cm                   INT             NOT NULL,
    width_cm                    INT             NOT NULL,
    height_cm                   INT             NOT NULL,
    fragile                     TINYINT(1)      NOT NULL DEFAULT 0,

    route_source                VARCHAR(25)     NOT NULL,
    distance_km                 DECIMAL(10, 2)  NOT NULL,
    estimated_duration_minutes  INT             NOT NULL,

    base_fare                   DECIMAL(10, 2)  NOT NULL,
    distance_fare               DECIMAL(10, 2)  NOT NULL,
    subtotal_fare               DECIMAL(10, 2)  NOT NULL,
    gst_applicable              TINYINT(1)      NOT NULL DEFAULT 0,
    gst_rate_percent            DECIMAL(5, 2)   NOT NULL DEFAULT 0.00,
    gst_amount                  DECIMAL(10, 2)  NOT NULL DEFAULT 0.00,
    total_fare                  DECIMAL(10, 2)  NOT NULL,

    recommended_vehicle_code    VARCHAR(30)     NOT NULL,
    ai_suggestion_note          VARCHAR(300)    NULL,

    status                      VARCHAR(30)     NOT NULL,
    cancellation_reason         VARCHAR(300)    NULL,
    cancelled_at                DATETIME(6)     NULL,
    picked_up_at                DATETIME(6)     NULL,
    delivered_at                DATETIME(6)     NULL,

    version                     BIGINT          NOT NULL DEFAULT 0,
    created_at                  DATETIME(6)     NOT NULL,
    updated_at                  DATETIME(6)     NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_shipments_tracking_code UNIQUE (tracking_code),
    CONSTRAINT fk_shipments_customer_user
        FOREIGN KEY (customer_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_shipments_assigned_driver
        FOREIGN KEY (assigned_driver_profile_id) REFERENCES driver_profiles (id) ON DELETE SET NULL,
    CONSTRAINT fk_shipments_vehicle_type
        FOREIGN KEY (vehicle_type_id) REFERENCES vehicle_types (id),
    INDEX idx_shipments_customer_created (customer_user_id, created_at),
    INDEX idx_shipments_status_city (status, pickup_city),
    INDEX idx_shipments_driver (assigned_driver_profile_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE shipment_status_history (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    shipment_id         BIGINT          NOT NULL,
    from_status         VARCHAR(30)     NULL,
    to_status           VARCHAR(30)     NOT NULL,
    changed_by_user_id  BIGINT          NULL,
    changed_by_role     VARCHAR(20)     NOT NULL,
    note                VARCHAR(300)    NULL,
    created_at          DATETIME(6)     NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_history_shipment
        FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE CASCADE,
    CONSTRAINT fk_shipment_history_user
        FOREIGN KEY (changed_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_shipment_history_shipment_created (shipment_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
