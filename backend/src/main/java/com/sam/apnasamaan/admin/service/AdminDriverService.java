package com.sam.apnasamaan.admin.service;

import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.RefreshTokenRepository;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.common.exception.ResourceNotFoundException;
import com.sam.apnasamaan.driver.dto.DriverProfileResponse;
import com.sam.apnasamaan.driver.entity.DriverApprovalStatus;
import com.sam.apnasamaan.driver.entity.DriverDocument;
import com.sam.apnasamaan.driver.entity.DriverDocumentType;
import com.sam.apnasamaan.driver.entity.DriverProfile;
import com.sam.apnasamaan.driver.mapper.DriverMapper;
import com.sam.apnasamaan.driver.repository.DriverDocumentRepository;
import com.sam.apnasamaan.driver.repository.DriverProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AdminDriverService {

    private static final int MAX_PAGE_SIZE = 50;

    private final DriverProfileRepository driverProfileRepository;
    private final DriverDocumentRepository driverDocumentRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DriverMapper driverMapper;

    public AdminDriverService(
            DriverProfileRepository driverProfileRepository,
            DriverDocumentRepository driverDocumentRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            DriverMapper driverMapper
    ) {
        this.driverProfileRepository = driverProfileRepository;
        this.driverDocumentRepository = driverDocumentRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.driverMapper = driverMapper;
    }

    @Transactional(readOnly = true)
    public PagedResponse<DriverProfileResponse> listDrivers(
            DriverApprovalStatus status,
            String search,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        String searchPattern = (search == null || search.isBlank())
                ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";

        Page<DriverProfile> profilesPage = driverProfileRepository.searchDriversForAdmin(
                status, searchPattern, pageable
        );

        List<Long> profileIds = profilesPage.getContent().stream()
                .map(DriverProfile::getId)
                .toList();

        // Batch-load documents for the whole page in one SQL query (avoids N+1 queries)
        Map<Long, List<DriverDocument>> docsByProfileId = profileIds.isEmpty()
                ? Map.of()
                : driverDocumentRepository.findByDriverProfileIdIn(profileIds).stream()
                        .sorted(Comparator.comparing(DriverDocument::getDocType))
                        .collect(Collectors.groupingBy(doc -> doc.getDriverProfile().getId()));

        Page<DriverProfileResponse> mappedPage = profilesPage.map(profile ->
                driverMapper.toProfileResponse(profile, docsByProfileId.getOrDefault(profile.getId(), List.of()))
        );

        return PagedResponse.from(mappedPage);
    }

    @Transactional(readOnly = true)
    public DriverProfileResponse getDriverById(Long driverProfileId) {
        DriverProfile profile = loadDriverProfile(driverProfileId);
        List<DriverDocument> docs = driverDocumentRepository.findByDriverProfileIdOrderByDocTypeAsc(driverProfileId);
        return driverMapper.toProfileResponse(profile, docs);
    }

    @Transactional
    public DriverProfileResponse approveDriver(Long driverProfileId, Long adminUserId) {
        DriverProfile profile = loadDriverProfile(driverProfileId);
        User admin = loadAdminUser(adminUserId);
        List<DriverDocument> docs = driverDocumentRepository.findByDriverProfileIdOrderByDocTypeAsc(driverProfileId);

        Set<DriverDocumentType> docTypes = docs.stream()
                .map(DriverDocument::getDocType)
                .collect(Collectors.toSet());
        if (!docTypes.containsAll(EnumSet.allOf(DriverDocumentType.class))) {
            throw new BadRequestException(
                    "Cannot approve driver: both DRIVING_LICENCE and VEHICLE_RC must be uploaded"
            );
        }

        profile.setApprovalStatus(DriverApprovalStatus.APPROVED);
        profile.setRejectionReason(null);
        profile.setReviewedBy(admin);
        profile.setReviewedAt(Instant.now());

        // Re-enable user login if this driver had previously been deactivated
        User driverUser = profile.getUser();
        if (!driverUser.isEnabled()) {
            driverUser.setEnabled(true);
            userRepository.save(driverUser);
        }

        DriverProfile saved = driverProfileRepository.save(profile);
        log.info("Admin userId={} APPROVED driverProfileId={}", adminUserId, driverProfileId);
        return driverMapper.toProfileResponse(saved, docs);
    }

    @Transactional
    public DriverProfileResponse rejectDriver(Long driverProfileId, Long adminUserId, String reason) {
        DriverProfile profile = loadDriverProfile(driverProfileId);
        User admin = loadAdminUser(adminUserId);
        List<DriverDocument> docs = driverDocumentRepository.findByDriverProfileIdOrderByDocTypeAsc(driverProfileId);

        profile.setApprovalStatus(DriverApprovalStatus.REJECTED);
        profile.setRejectionReason(reason.trim());
        profile.setOnline(false);
        profile.setReviewedBy(admin);
        profile.setReviewedAt(Instant.now());

        DriverProfile saved = driverProfileRepository.save(profile);
        log.info("Admin userId={} REJECTED driverProfileId={}", adminUserId, driverProfileId);
        return driverMapper.toProfileResponse(saved, docs);
    }

    @Transactional
    public DriverProfileResponse deactivateDriver(Long driverProfileId, Long adminUserId) {
        DriverProfile profile = loadDriverProfile(driverProfileId);
        User admin = loadAdminUser(adminUserId);
        List<DriverDocument> docs = driverDocumentRepository.findByDriverProfileIdOrderByDocTypeAsc(driverProfileId);

        profile.setApprovalStatus(DriverApprovalStatus.DEACTIVATED);
        profile.setOnline(false);
        profile.setReviewedBy(admin);
        profile.setReviewedAt(Instant.now());

        User driverUser = profile.getUser();
        driverUser.setEnabled(false);
        userRepository.save(driverUser);

        // Immediately revoke active refresh tokens so deactivated driver cannot stay logged in
        refreshTokenRepository.revokeAllActiveByUserId(driverUser.getId());

        DriverProfile saved = driverProfileRepository.save(profile);
        log.info("Admin userId={} DEACTIVATED driverProfileId={}", adminUserId, driverProfileId);
        return driverMapper.toProfileResponse(saved, docs);
    }

    private DriverProfile loadDriverProfile(Long driverProfileId) {
        return driverProfileRepository.findByIdWithDetails(driverProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
    }

    private User loadAdminUser(Long adminUserId) {
        return userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));
    }
}
