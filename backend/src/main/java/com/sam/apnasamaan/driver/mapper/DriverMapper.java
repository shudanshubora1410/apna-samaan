package com.sam.apnasamaan.driver.mapper;

import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.driver.dto.DriverDocumentResponse;
import com.sam.apnasamaan.driver.dto.DriverProfileResponse;
import com.sam.apnasamaan.driver.entity.DriverDocument;
import com.sam.apnasamaan.driver.entity.DriverProfile;
import com.sam.apnasamaan.shipment.entity.VehicleType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DriverMapper {

    public DriverDocumentResponse toDocumentResponse(DriverDocument doc) {
        return new DriverDocumentResponse(
                doc.getId(),
                doc.getDocType(),
                doc.getOriginalFilename(),
                doc.getContentType(),
                doc.getFileSizeBytes(),
                doc.getUploadedAt(),
                "/api/v1/driver/documents/" + doc.getId()
        );
    }

    public DriverProfileResponse toProfileResponse(DriverProfile profile, List<DriverDocument> documents) {
        User user = profile.getUser();
        VehicleType vehicleType = profile.getVehicleType();
        List<DriverDocumentResponse> docDtos = documents.stream()
                .map(this::toDocumentResponse)
                .toList();

        return new DriverProfileResponse(
                profile.getId(),
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                vehicleType.getCode(),
                vehicleType.getName(),
                profile.getVehicleNumber(),
                profile.getCity(),
                profile.getApprovalStatus(),
                profile.getRejectionReason(),
                profile.isOnline(),
                profile.isAvailable(),
                user.isEnabled(),
                docDtos,
                profile.getReviewedAt(),
                profile.getCreatedAt()
        );
    }
}
