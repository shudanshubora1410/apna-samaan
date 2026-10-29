package com.sam.apnasamaan.shipment.repository;

import com.sam.apnasamaan.shipment.entity.DriverJobOffer;
import com.sam.apnasamaan.shipment.entity.DriverJobOfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DriverJobOfferRepository extends JpaRepository<DriverJobOffer, Long> {

    long countByShipmentId(Long shipmentId);

    boolean existsByShipmentIdAndStatusAndExpiresAtAfter(
            Long shipmentId,
            DriverJobOfferStatus status,
            Instant now
    );

    @Query("""
            SELECT o FROM DriverJobOffer o
            JOIN FETCH o.shipment s
            JOIN FETCH s.customer
            JOIN FETCH s.vehicleType
            JOIN FETCH o.driverProfile dp
            JOIN FETCH dp.user
            WHERE o.id = :offerId
            """)
    Optional<DriverJobOffer> findByIdWithDetails(@Param("offerId") Long offerId);

    @Query("""
            SELECT o FROM DriverJobOffer o
            JOIN FETCH o.shipment s
            JOIN FETCH s.vehicleType
            WHERE o.driverProfile.id = :driverProfileId
              AND o.status = 'PENDING'
              AND o.expiresAt > :now
            ORDER BY o.offeredAt DESC
            """)
    List<DriverJobOffer> findActivePendingOffersForDriver(
            @Param("driverProfileId") Long driverProfileId,
            @Param("now") Instant now
    );

    @Query("""
            SELECT o FROM DriverJobOffer o
            JOIN FETCH o.shipment s
            WHERE o.status = 'PENDING'
              AND o.expiresAt <= :now
            ORDER BY o.expiresAt ASC
            """)
    List<DriverJobOffer> findExpiredPendingOffers(@Param("now") Instant now);

    @Query("""
            SELECT o FROM DriverJobOffer o
            WHERE o.shipment.id = :shipmentId
              AND o.status = 'PENDING'
            """)
    List<DriverJobOffer> findPendingOffersByShipmentId(@Param("shipmentId") Long shipmentId);

    @Query("""
            SELECT o FROM DriverJobOffer o
            JOIN FETCH o.shipment s
            WHERE o.driverProfile.id = :driverProfileId
              AND o.status = 'PENDING'
            """)
    List<DriverJobOffer> findPendingOffersByDriverProfileId(@Param("driverProfileId") Long driverProfileId);
}
