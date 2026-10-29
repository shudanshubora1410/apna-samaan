package com.sam.apnasamaan.payment.repository;

import com.sam.apnasamaan.payment.entity.Payment;
import com.sam.apnasamaan.payment.entity.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.shipment s
            JOIN FETCH p.customer c
            WHERE p.razorpayOrderId = :razorpayOrderId
            """)
    Optional<Payment> findByRazorpayOrderIdWithDetails(@Param("razorpayOrderId") String razorpayOrderId);

    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.shipment s
            JOIN FETCH p.customer c
            WHERE p.razorpayPaymentId = :razorpayPaymentId
            """)
    Optional<Payment> findByRazorpayPaymentIdWithDetails(@Param("razorpayPaymentId") String razorpayPaymentId);

    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.shipment s
            JOIN FETCH p.customer c
            WHERE p.id = :id
            """)
    Optional<Payment> findByIdWithDetails(@Param("id") Long id);

    @Query("""
            SELECT p FROM Payment p
            JOIN FETCH p.shipment s
            JOIN FETCH p.customer c
            WHERE s.id = :shipmentId
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Payment> findByShipmentIdOrderByCreatedAtDesc(@Param("shipmentId") Long shipmentId);

    long countByShipmentId(Long shipmentId);

    @Query(
            value = """
                    SELECT p FROM Payment p
                    JOIN FETCH p.shipment s
                    JOIN FETCH p.customer c
                    WHERE (:status IS NULL OR p.status = :status)
                      AND (:searchPattern IS NULL
                           OR LOWER(s.trackingCode) LIKE :searchPattern
                           OR LOWER(p.razorpayOrderId) LIKE :searchPattern
                           OR LOWER(COALESCE(p.razorpayPaymentId, '')) LIKE :searchPattern
                           OR LOWER(c.name) LIKE :searchPattern
                           OR LOWER(c.email) LIKE :searchPattern)
                    """,
            countQuery = """
                    SELECT COUNT(p) FROM Payment p
                    JOIN p.shipment s
                    JOIN p.customer c
                    WHERE (:status IS NULL OR p.status = :status)
                      AND (:searchPattern IS NULL
                           OR LOWER(s.trackingCode) LIKE :searchPattern
                           OR LOWER(p.razorpayOrderId) LIKE :searchPattern
                           OR LOWER(COALESCE(p.razorpayPaymentId, '')) LIKE :searchPattern
                           OR LOWER(c.name) LIKE :searchPattern
                           OR LOWER(c.email) LIKE :searchPattern)
                    """
    )
    Page<Payment> searchPaymentsForAdmin(
            @Param("status") PaymentStatus status,
            @Param("searchPattern") String searchPattern,
            Pageable pageable
    );
}
