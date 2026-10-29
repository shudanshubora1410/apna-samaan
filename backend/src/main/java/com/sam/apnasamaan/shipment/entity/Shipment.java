package com.sam.apnasamaan.shipment.entity;

import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.driver.entity.DriverProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "shipments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracking_code", nullable = false, unique = true, length = 24)
    private String trackingCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_user_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_driver_profile_id")
    private DriverProfile assignedDriver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_type_id", nullable = false)
    private VehicleType vehicleType;

    @Column(name = "pickup_address", nullable = false, length = 300)
    private String pickupAddress;

    @Column(name = "pickup_city", nullable = false, length = 80)
    private String pickupCity;

    @Column(name = "pickup_lat", nullable = false)
    private double pickupLat;

    @Column(name = "pickup_lng", nullable = false)
    private double pickupLng;

    @Column(name = "pickup_contact_name", nullable = false, length = 100)
    private String pickupContactName;

    @Column(name = "pickup_contact_phone", nullable = false, length = 15)
    private String pickupContactPhone;

    @Column(name = "drop_address", nullable = false, length = 300)
    private String dropAddress;

    @Column(name = "drop_city", nullable = false, length = 80)
    private String dropCity;

    @Column(name = "drop_lat", nullable = false)
    private double dropLat;

    @Column(name = "drop_lng", nullable = false)
    private double dropLng;

    @Column(name = "drop_contact_name", nullable = false, length = 100)
    private String dropContactName;

    @Column(name = "drop_contact_phone", nullable = false, length = 15)
    private String dropContactPhone;

    @Column(name = "package_description", nullable = false, length = 255)
    private String packageDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "package_category", nullable = false, length = 40)
    private PackageCategory packageCategory;

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "length_cm", nullable = false)
    private int lengthCm;

    @Column(name = "width_cm", nullable = false)
    private int widthCm;

    @Column(name = "height_cm", nullable = false)
    private int heightCm;

    @Builder.Default
    @Column(name = "fragile", nullable = false)
    private boolean fragile = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "route_source", nullable = false, length = 25)
    private RouteSource routeSource;

    @Column(name = "distance_km", nullable = false, precision = 10, scale = 2)
    private BigDecimal distanceKm;

    @Column(name = "estimated_duration_minutes", nullable = false)
    private int estimatedDurationMinutes;

    @Column(name = "base_fare", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseFare;

    @Column(name = "distance_fare", nullable = false, precision = 10, scale = 2)
    private BigDecimal distanceFare;

    @Column(name = "subtotal_fare", nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotalFare;

    @Builder.Default
    @Column(name = "gst_applicable", nullable = false)
    private boolean gstApplicable = false;

    @Column(name = "gst_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstRatePercent;

    @Column(name = "gst_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal gstAmount;

    @Column(name = "total_fare", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalFare;

    @Column(name = "recommended_vehicle_code", nullable = false, length = 30)
    private String recommendedVehicleCode;

    @Column(name = "ai_suggestion_note", length = 300)
    private String aiSuggestionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ShipmentStatus status;

    @Column(name = "cancellation_reason", length = 300)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "search_started_at")
    private Instant searchStartedAt;

    // Optimistic locking guards against concurrent driver accept / status race conditions in Phase 5
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
