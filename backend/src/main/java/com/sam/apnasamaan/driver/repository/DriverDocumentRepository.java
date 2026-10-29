package com.sam.apnasamaan.driver.repository;

import com.sam.apnasamaan.driver.entity.DriverDocument;
import com.sam.apnasamaan.driver.entity.DriverDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, Long> {

    List<DriverDocument> findByDriverProfileIdOrderByDocTypeAsc(Long driverProfileId);

    List<DriverDocument> findByDriverProfileIdIn(Collection<Long> driverProfileIds);

    Optional<DriverDocument> findByDriverProfileIdAndDocType(Long driverProfileId, DriverDocumentType docType);

    @Query("""
            SELECT dd FROM DriverDocument dd
            JOIN FETCH dd.driverProfile dp
            JOIN FETCH dp.user
            WHERE dd.id = :documentId
            """)
    Optional<DriverDocument> findByIdWithOwner(@Param("documentId") Long documentId);
}
