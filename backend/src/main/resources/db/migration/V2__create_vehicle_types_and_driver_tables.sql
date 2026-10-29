-- =============================================================================
-- V2: Vehicle Types (seeded), Driver Profiles, and Driver Documents (Phase 2)
-- =============================================================================

CREATE TABLE vehicle_types (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(30) NOT NULL,
    name VARCHAR(60) NOT NULL,
    description VARCHAR(255) NOT NULL,
    max_weight_kg DECIMAL(10, 2) NOT NULL,
    max_length_cm INT NOT NULL,
    max_width_cm INT NOT NULL,
    max_height_cm INT NOT NULL,
    base_fare DECIMAL(10, 2) NOT NULL,
    per_km_rate DECIMAL(10, 2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_vehicle_types PRIMARY KEY (id),
    CONSTRAINT uk_vehicle_types_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed the 4 official Indian goods-delivery vehicle tiers (INR fares)
INSERT INTO vehicle_types (
    code, name, description,
    max_weight_kg, max_length_cm, max_width_cm, max_height_cm,
    base_fare, per_km_rate, active, created_at
) VALUES
    ('BIKE', 'Bike', 'Two-wheeler for small parcels, documents, and light boxes',
     20.00, 40, 40, 40, 40.00, 8.50, TRUE, UTC_TIMESTAMP(6)),
    ('CARGO_AUTO', 'Cargo Auto', 'Three-wheeler cargo loader for cartons, appliances, and shop stock',
     500.00, 170, 120, 120, 180.00, 18.00, TRUE, UTC_TIMESTAMP(6)),
    ('MINI_TRUCK', 'Mini Truck', 'Compact 4-wheeler (Tata Ace class) for furniture and house shifting',
     1000.00, 220, 150, 150, 350.00, 26.00, TRUE, UTC_TIMESTAMP(6)),
    ('TRUCK', 'Truck', '14ft commercial truck for bulk wholesale and inter-city loads',
     3500.00, 420, 190, 190, 900.00, 45.00, TRUE, UTC_TIMESTAMP(6));

CREATE TABLE driver_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    vehicle_type_id BIGINT NOT NULL,
    vehicle_number VARCHAR(20) NOT NULL,
    city VARCHAR(80) NOT NULL,
    approval_status VARCHAR(20) NOT NULL,
    rejection_reason VARCHAR(500) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(6) NULL,
    is_online BOOLEAN NOT NULL DEFAULT FALSE,
    is_available BOOLEAN NOT NULL DEFAULT TRUE,
    current_lat DOUBLE NULL,
    current_lng DOUBLE NULL,
    location_updated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_driver_profiles PRIMARY KEY (id),
    CONSTRAINT uk_driver_profiles_user UNIQUE (user_id),
    CONSTRAINT uk_driver_profiles_vehicle_number UNIQUE (vehicle_number),
    CONSTRAINT fk_driver_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_driver_profiles_vehicle_type FOREIGN KEY (vehicle_type_id) REFERENCES vehicle_types (id),
    CONSTRAINT fk_driver_profiles_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_driver_profiles_approval_status ON driver_profiles (approval_status);
CREATE INDEX idx_driver_profiles_matching ON driver_profiles (city, approval_status, is_online, is_available, vehicle_type_id);

CREATE TABLE driver_documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    driver_profile_id BIGINT NOT NULL,
    doc_type VARCHAR(30) NOT NULL,
    stored_filename VARCHAR(120) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_driver_documents PRIMARY KEY (id),
    CONSTRAINT uk_driver_documents_stored_filename UNIQUE (stored_filename),
    CONSTRAINT uk_driver_documents_profile_type UNIQUE (driver_profile_id, doc_type),
    CONSTRAINT fk_driver_documents_profile FOREIGN KEY (driver_profile_id) REFERENCES driver_profiles (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
