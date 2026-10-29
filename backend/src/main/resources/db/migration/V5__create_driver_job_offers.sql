-- =============================================================================
-- V5: Sequential Driver Job Offers & Search Window Tracking (Phase 5)
-- Supports Haversine-ranked 60s sequential job offers, optimistic locking on
-- concurrent driver accept, and scheduled search-timeout refunds.
-- =============================================================================

ALTER TABLE shipments
    ADD COLUMN search_started_at DATETIME(6) NULL AFTER delivered_at;

CREATE TABLE driver_job_offers (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    shipment_id             BIGINT          NOT NULL,
    driver_profile_id       BIGINT          NOT NULL,
    sequence_number         INT             NOT NULL,
    distance_to_pickup_km   DECIMAL(10, 2)  NULL,
    status                  VARCHAR(20)     NOT NULL,
    offered_at              DATETIME(6)     NOT NULL,
    expires_at              DATETIME(6)     NOT NULL,
    responded_at            DATETIME(6)     NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              DATETIME(6)     NOT NULL,
    updated_at              DATETIME(6)     NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_job_offers_shipment_driver UNIQUE (shipment_id, driver_profile_id),
    CONSTRAINT fk_job_offers_shipment
        FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_offers_driver_profile
        FOREIGN KEY (driver_profile_id) REFERENCES driver_profiles (id) ON DELETE CASCADE,
    INDEX idx_job_offers_driver_status (driver_profile_id, status, expires_at),
    INDEX idx_job_offers_status_expires (status, expires_at),
    INDEX idx_job_offers_shipment_seq (shipment_id, sequence_number)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
