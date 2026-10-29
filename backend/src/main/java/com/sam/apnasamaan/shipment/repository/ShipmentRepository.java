package com.sam.apnasamaan.shipment.repository;

import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    boolean existsByTrackingCode(String trackingCode);

    @Query("""
            SELECT s FROM Shipment s
            JOIN FETCH s.customer
            JOIN FETCH s.vehicleType
            LEFT JOIN FETCH s.assignedDriver d
            LEFT JOIN FETCH d.user
            WHERE s.id = :id
            """)
    Optional<Shipment> findByIdWithDetails(@Param("id") Long id);

    @Query(
            value = """
                    SELECT s FROM Shipment s
                    JOIN FETCH s.customer c
                    JOIN FETCH s.vehicleType
                    LEFT JOIN FETCH s.assignedDriver d
                    LEFT JOIN FETCH d.user
                    WHERE c.id = :customerId
                      AND (:status IS NULL OR s.status = :status)
                    """,
            countQuery = """
                    SELECT COUNT(s) FROM Shipment s
                    WHERE s.customer.id = :customerId
                      AND (:status IS NULL OR s.status = :status)
                    """
    )
    Page<Shipment> findCustomerShipments(
            @Param("customerId") Long customerId,
            @Param("status") ShipmentStatus status,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT s FROM Shipment s
                    JOIN FETCH s.customer c
                    JOIN FETCH s.vehicleType
                    LEFT JOIN FETCH s.assignedDriver d
                    LEFT JOIN FETCH d.user
                    WHERE (:status IS NULL OR s.status = :status)
                      AND (:searchPattern IS NULL
                           OR LOWER(s.trackingCode) LIKE :searchPattern
                           OR LOWER(s.pickupCity) LIKE :searchPattern
                           OR LOWER(s.dropCity) LIKE :searchPattern
                           OR LOWER(c.name) LIKE :searchPattern
                           OR LOWER(c.email) LIKE :searchPattern)
                    """,
            countQuery = """
                    SELECT COUNT(s) FROM Shipment s
                    JOIN s.customer c
                    WHERE (:status IS NULL OR s.status = :status)
                      AND (:searchPattern IS NULL
                           OR LOWER(s.trackingCode) LIKE :searchPattern
                           OR LOWER(s.pickupCity) LIKE :searchPattern
                           OR LOWER(s.dropCity) LIKE :searchPattern
                           OR LOWER(c.name) LIKE :searchPattern
                           OR LOWER(c.email) LIKE :searchPattern)
                    """
    )
    Page<Shipment> searchShipmentsForAdmin(
            @Param("status") ShipmentStatus status,
            @Param("searchPattern") String searchPattern,
            Pageable pageable
    );

    @Query("""
            SELECT s FROM Shipment s
            JOIN FETCH s.customer
            JOIN FETCH s.vehicleType
            WHERE s.status = com.sam.apnasamaan.shipment.entity.ShipmentStatus.SEARCHING_DRIVER
            ORDER BY s.searchStartedAt ASC, s.id ASC
            """)
    List<Shipment> findShipmentsSearchingForDriver();

    @Query("""
            SELECT s FROM Shipment s
            JOIN FETCH s.customer
            JOIN FETCH s.vehicleType
            JOIN FETCH s.assignedDriver d
            JOIN FETCH d.user
            WHERE d.id = :driverProfileId
              AND s.status IN :activeStatuses
            ORDER BY s.updatedAt DESC
            """)
    List<Shipment> findActiveShipmentsForDriver(
            @Param("driverProfileId") Long driverProfileId,
            @Param("activeStatuses") Collection<ShipmentStatus> activeStatuses
    );

    @Query(
            value = """
                    SELECT s FROM Shipment s
                    JOIN FETCH s.customer
                    JOIN FETCH s.vehicleType
                    JOIN FETCH s.assignedDriver d
                    JOIN FETCH d.user
                    WHERE d.id = :driverProfileId
                      AND (:status IS NULL OR s.status = :status)
                    """,
            countQuery = """
                    SELECT COUNT(s) FROM Shipment s
                    WHERE s.assignedDriver.id = :driverProfileId
                      AND (:status IS NULL OR s.status = :status)
                    """
    )
    Page<Shipment> findDriverShipments(
            @Param("driverProfileId") Long driverProfileId,
            @Param("status") ShipmentStatus status,
            Pageable pageable
    );
}
