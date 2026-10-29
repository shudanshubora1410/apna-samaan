package com.sam.apnasamaan.driver.repository;

import com.sam.apnasamaan.driver.entity.DriverApprovalStatus;
import com.sam.apnasamaan.driver.entity.DriverProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DriverProfileRepository extends JpaRepository<DriverProfile, Long> {

    boolean existsByVehicleNumberIgnoreCase(String vehicleNumber);

    @Query("""
            SELECT dp FROM DriverProfile dp
            JOIN FETCH dp.user
            JOIN FETCH dp.vehicleType
            WHERE dp.user.id = :userId
            """)
    Optional<DriverProfile> findByUserIdWithDetails(@Param("userId") Long userId);

    @Query("""
            SELECT dp FROM DriverProfile dp
            JOIN FETCH dp.user
            JOIN FETCH dp.vehicleType
            WHERE dp.id = :id
            """)
    Optional<DriverProfile> findByIdWithDetails(@Param("id") Long id);

    @Query(
            value = """
                    SELECT dp FROM DriverProfile dp
                    JOIN FETCH dp.user u
                    JOIN FETCH dp.vehicleType vt
                    WHERE (:status IS NULL OR dp.approvalStatus = :status)
                      AND (:searchPattern IS NULL
                           OR LOWER(u.name) LIKE :searchPattern
                           OR LOWER(u.email) LIKE :searchPattern
                           OR LOWER(u.phone) LIKE :searchPattern
                           OR LOWER(dp.vehicleNumber) LIKE :searchPattern
                           OR LOWER(dp.city) LIKE :searchPattern)
                    """,
            countQuery = """
                    SELECT COUNT(dp) FROM DriverProfile dp
                    JOIN dp.user u
                    WHERE (:status IS NULL OR dp.approvalStatus = :status)
                      AND (:searchPattern IS NULL
                           OR LOWER(u.name) LIKE :searchPattern
                           OR LOWER(u.email) LIKE :searchPattern
                           OR LOWER(u.phone) LIKE :searchPattern
                           OR LOWER(dp.vehicleNumber) LIKE :searchPattern
                           OR LOWER(dp.city) LIKE :searchPattern)
                    """
    )
    Page<DriverProfile> searchDriversForAdmin(
            @Param("status") DriverApprovalStatus status,
            @Param("searchPattern") String searchPattern,
            Pageable pageable
    );

    /**
     * Finds APPROVED + ONLINE + AVAILABLE drivers in the same city with the required vehicle
     * who have not yet been offered this shipment and do not hold another active offer.
     */
    @Query("""
            SELECT dp FROM DriverProfile dp
            JOIN FETCH dp.user u
            JOIN FETCH dp.vehicleType vt
            WHERE dp.approvalStatus = com.sam.apnasamaan.driver.entity.DriverApprovalStatus.APPROVED
              AND dp.online = true
              AND dp.available = true
              AND u.enabled = true
              AND LOWER(dp.city) = LOWER(:pickupCity)
              AND vt.id = :vehicleTypeId
              AND NOT EXISTS (
                  SELECT 1 FROM com.sam.apnasamaan.shipment.entity.DriverJobOffer prev
                  WHERE prev.shipment.id = :shipmentId
                    AND prev.driverProfile.id = dp.id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM com.sam.apnasamaan.shipment.entity.DriverJobOffer active
                  WHERE active.driverProfile.id = dp.id
                    AND active.status = com.sam.apnasamaan.shipment.entity.DriverJobOfferStatus.PENDING
                    AND active.expiresAt > :now
              )
            """)
    List<DriverProfile> findEligibleDriversForShipment(
            @Param("pickupCity") String pickupCity,
            @Param("vehicleTypeId") Long vehicleTypeId,
            @Param("shipmentId") Long shipmentId,
            @Param("now") Instant now
    );

    /**
     * Atomic conditional update guaranteeing a driver cannot accept two jobs concurrently.
     */
    @Modifying
    @Query("""
            UPDATE DriverProfile dp
            SET dp.available = false
            WHERE dp.id = :driverProfileId
              AND dp.available = true
              AND dp.online = true
              AND dp.approvalStatus = com.sam.apnasamaan.driver.entity.DriverApprovalStatus.APPROVED
            """)
    int claimDriverIfAvailable(@Param("driverProfileId") Long driverProfileId);

    @Modifying
    @Query("""
            UPDATE DriverProfile dp
            SET dp.available = true
            WHERE dp.id = :driverProfileId
            """)
    void releaseDriverAvailability(@Param("driverProfileId") Long driverProfileId);
}
