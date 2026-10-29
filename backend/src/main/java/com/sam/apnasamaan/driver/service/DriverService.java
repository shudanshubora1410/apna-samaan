package com.sam.apnasamaan.driver.service;

import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.auth.service.AuthService;
import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.common.exception.ConflictException;
import com.sam.apnasamaan.common.exception.ForbiddenOperationException;
import com.sam.apnasamaan.common.exception.ResourceNotFoundException;
import com.sam.apnasamaan.driver.dto.DriverDocumentResponse;
import com.sam.apnasamaan.driver.dto.DriverLocationResponse;
import com.sam.apnasamaan.driver.dto.DriverLocationUpdateRequest;
import com.sam.apnasamaan.driver.dto.DriverProfileResponse;
import com.sam.apnasamaan.driver.dto.DriverRegisterForm;
import com.sam.apnasamaan.driver.dto.DriverRegistrationResponse;
import com.sam.apnasamaan.driver.entity.DriverApprovalStatus;
import com.sam.apnasamaan.driver.entity.DriverDocument;
import com.sam.apnasamaan.driver.entity.DriverDocumentType;
import com.sam.apnasamaan.driver.entity.DriverProfile;
import com.sam.apnasamaan.driver.event.DriverOnlineEvent;
import com.sam.apnasamaan.driver.mapper.DriverMapper;
import com.sam.apnasamaan.driver.repository.DriverDocumentRepository;
import com.sam.apnasamaan.driver.repository.DriverProfileRepository;
import com.sam.apnasamaan.shipment.entity.DriverJobOffer;
import com.sam.apnasamaan.shipment.entity.DriverJobOfferStatus;
import com.sam.apnasamaan.shipment.entity.VehicleType;
import com.sam.apnasamaan.shipment.event.ShipmentReadyForMatchingEvent;
import com.sam.apnasamaan.shipment.repository.DriverJobOfferRepository;
import com.sam.apnasamaan.shipment.service.VehicleTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DriverService {

    // Matches standard Indian RTO plates (e.g. DL01AB1234, MH12A1234) and Bharat series (e.g. 24BH1234AB)
    private static final Pattern INDIAN_VEHICLE_NUMBER_PATTERN =
            Pattern.compile("^([A-Z]{2}\\d{1,2}[A-Z]{0,3}\\d{4}|\\d{2}BH\\d{4}[A-Z]{1,2})$");

    private final UserRepository userRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final DriverDocumentRepository driverDocumentRepository;
    private final VehicleTypeService vehicleTypeService;
    private final DocumentStorageService documentStorageService;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final DriverMapper driverMapper;
    private final DriverJobOfferRepository offerRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DriverService(
            UserRepository userRepository,
            DriverProfileRepository driverProfileRepository,
            DriverDocumentRepository driverDocumentRepository,
            VehicleTypeService vehicleTypeService,
            DocumentStorageService documentStorageService,
            PasswordEncoder passwordEncoder,
            AuthService authService,
            DriverMapper driverMapper,
            DriverJobOfferRepository offerRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.userRepository = userRepository;
        this.driverProfileRepository = driverProfileRepository;
        this.driverDocumentRepository = driverDocumentRepository;
        this.vehicleTypeService = vehicleTypeService;
        this.documentStorageService = documentStorageService;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.driverMapper = driverMapper;
        this.offerRepository = offerRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DriverRegistrationResponse registerDriver(
            DriverRegisterForm form,
            MultipartFile drivingLicenceFile,
            MultipartFile vehicleRcFile
    ) {
        String normalizedEmail = form.email().trim().toLowerCase(Locale.ROOT);
        String normalizedVehicleNumber = normalizeAndValidateVehicleNumber(form.vehicleNumber());
        String normalizedCity = normalizeCity(form.city());

        // Validate DB uniqueness and vehicle type before writing any files to disk
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("An account with this email already exists");
        }
        if (driverProfileRepository.existsByVehicleNumberIgnoreCase(normalizedVehicleNumber)) {
            throw new ConflictException("A driver with this vehicle registration number already exists");
        }
        VehicleType vehicleType = vehicleTypeService.getActiveByCodeOrThrow(form.vehicleTypeCode());

        DocumentStorageService.StoredDocument storedDl = null;
        DocumentStorageService.StoredDocument storedRc = null;

        try {
            storedDl = documentStorageService.store(drivingLicenceFile, "Driving licence");
            storedRc = documentStorageService.store(vehicleRcFile, "Vehicle RC");

            User driverUser = User.builder()
                    .name(form.name().trim())
                    .email(normalizedEmail)
                    .phone(form.phone().trim())
                    .passwordHash(passwordEncoder.encode(form.password()))
                    .role(Role.DRIVER)
                    .enabled(true)
                    .build();
            User savedUser = userRepository.save(driverUser);

            DriverProfile profile = DriverProfile.builder()
                    .user(savedUser)
                    .vehicleType(vehicleType)
                    .vehicleNumber(normalizedVehicleNumber)
                    .city(normalizedCity)
                    .approvalStatus(DriverApprovalStatus.PENDING)
                    .online(false)
                    .available(true)
                    .build();
            DriverProfile savedProfile = driverProfileRepository.save(profile);

            DriverDocument dlEntity = saveDocumentRow(savedProfile, DriverDocumentType.DRIVING_LICENCE, storedDl);
            DriverDocument rcEntity = saveDocumentRow(savedProfile, DriverDocumentType.VEHICLE_RC, storedRc);

            AuthResponse authResponse = authService.issueTokensForUser(savedUser);
            DriverProfileResponse profileResponse = driverMapper.toProfileResponse(
                    savedProfile, List.of(dlEntity, rcEntity)
            );

            log.info("Registered new DRIVER userId={} driverProfileId={} city={} vehicleType={}",
                    savedUser.getId(), savedProfile.getId(), normalizedCity, vehicleType.getCode());

            return new DriverRegistrationResponse(authResponse, profileResponse);
        } catch (RuntimeException ex) {
            // Clean up any newly written UUID files if registration fails mid-way
            if (storedDl != null) {
                documentStorageService.deleteQuietly(storedDl.storedFilename());
            }
            if (storedRc != null) {
                documentStorageService.deleteQuietly(storedRc.storedFilename());
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public DriverProfileResponse getMyProfile(UserPrincipal principal) {
        DriverProfile profile = loadProfileForUser(principal.getId());
        List<DriverDocument> docs = driverDocumentRepository.findByDriverProfileIdOrderByDocTypeAsc(profile.getId());
        return driverMapper.toProfileResponse(profile, docs);
    }

    @Transactional
    public DriverDocumentResponse uploadOrReplaceDocument(
            UserPrincipal principal,
            DriverDocumentType docType,
            MultipartFile file
    ) {
        DriverProfile profile = loadProfileForUser(principal.getId());
        if (profile.getApprovalStatus() == DriverApprovalStatus.DEACTIVATED) {
            throw new ForbiddenOperationException("Deactivated driver accounts cannot modify documents");
        }

        String label = docType == DriverDocumentType.DRIVING_LICENCE ? "Driving licence" : "Vehicle RC";
        DocumentStorageService.StoredDocument newlyStored = documentStorageService.store(file, label);
        String oldFilenameToDelete = null;

        try {
            DriverDocument document = driverDocumentRepository
                    .findByDriverProfileIdAndDocType(profile.getId(), docType)
                    .orElseGet(() -> DriverDocument.builder()
                            .driverProfile(profile)
                            .docType(docType)
                            .build());

            oldFilenameToDelete = document.getStoredFilename();
            document.setStoredFilename(newlyStored.storedFilename());
            document.setOriginalFilename(newlyStored.originalFilename());
            document.setContentType(newlyStored.contentType());
            document.setFileSizeBytes(newlyStored.fileSizeBytes());
            document.setUploadedAt(Instant.now());

            DriverDocument savedDoc = driverDocumentRepository.saveAndFlush(document);

            // Re-queue rejected drivers back to PENDING when they upload a corrected document
            if (profile.getApprovalStatus() == DriverApprovalStatus.REJECTED) {
                profile.setApprovalStatus(DriverApprovalStatus.PENDING);
                profile.setRejectionReason(null);
                driverProfileRepository.save(profile);
            }

            if (oldFilenameToDelete != null) {
                documentStorageService.deleteQuietly(oldFilenameToDelete);
            }

            log.info("Updated {} document for driverProfileId={}", docType, profile.getId());
            return driverMapper.toDocumentResponse(savedDoc);
        } catch (RuntimeException ex) {
            documentStorageService.deleteQuietly(newlyStored.storedFilename());
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public DownloadedDocument downloadDocument(Long documentId, UserPrincipal requester) {
        DriverDocument doc = driverDocumentRepository.findByIdWithOwner(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver document not found"));

        Long ownerUserId = doc.getDriverProfile().getUser().getId();
        boolean isOwnerDriver = requester.getRole() == Role.DRIVER && ownerUserId.equals(requester.getId());
        boolean isAdmin = requester.getRole() == Role.ADMIN;

        // Enforce private access: only the owning driver or an admin can ever read a document
        if (!isOwnerDriver && !isAdmin) {
            throw new ForbiddenOperationException("You do not have permission to view this driver document");
        }

        Resource resource = documentStorageService.loadAsResource(doc.getStoredFilename());
        return new DownloadedDocument(
                resource,
                doc.getContentType(),
                doc.getOriginalFilename(),
                doc.getFileSizeBytes()
        );
    }

    @Transactional
    public DriverProfileResponse updateOnlineStatus(UserPrincipal principal, boolean online) {
        DriverProfile profile = loadProfileForUser(principal.getId());
        List<DriverDocument> docs = driverDocumentRepository.findByDriverProfileIdOrderByDocTypeAsc(profile.getId());

        if (online) {
            if (profile.getApprovalStatus() != DriverApprovalStatus.APPROVED) {
                throw new ForbiddenOperationException(
                        "Only APPROVED drivers can go online. Current status: " + profile.getApprovalStatus()
                );
            }
            Set<DriverDocumentType> presentTypes = docs.stream()
                    .map(DriverDocument::getDocType)
                    .collect(Collectors.toSet());
            if (!presentTypes.containsAll(EnumSet.allOf(DriverDocumentType.class))) {
                throw new BadRequestException("Both driving licence and vehicle RC must be uploaded before going online");
            }
        } else {
            if (!profile.isAvailable()) {
                throw new BadRequestException("Complete your active delivery before switching offline");
            }
        }

        profile.setOnline(online);
        DriverProfile saved = driverProfileRepository.save(profile);
        log.info("Driver profileId={} toggled online={}", saved.getId(), online);

        if (online && saved.isAvailable()) {
            eventPublisher.publishEvent(new DriverOnlineEvent(saved.getId(), saved.getCity()));
        } else if (!online) {
            // Release any pending 60s offers sitting with this driver so those shipments advance immediately
            List<DriverJobOffer> pendingOffers = offerRepository.findPendingOffersByDriverProfileId(saved.getId());
            Instant now = Instant.now();
            for (DriverJobOffer offer : pendingOffers) {
                offer.setStatus(DriverJobOfferStatus.CANCELLED);
                offer.setRespondedAt(now);
                offerRepository.save(offer);
                eventPublisher.publishEvent(new ShipmentReadyForMatchingEvent(offer.getShipment().getId()));
            }
        }

        return driverMapper.toProfileResponse(saved, docs);
    }

    @Transactional
    public DriverLocationResponse updateLocation(
            UserPrincipal principal,
            DriverLocationUpdateRequest request
    ) {
        DriverProfile profile = loadProfileForUser(principal.getId());
        if (profile.getApprovalStatus() != DriverApprovalStatus.APPROVED) {
            throw new ForbiddenOperationException("Only APPROVED drivers can broadcast live GPS location");
        }

        Instant now = Instant.now();
        profile.setCurrentLat(request.lat());
        profile.setCurrentLng(request.lng());
        profile.setLocationUpdatedAt(now);
        DriverProfile saved = driverProfileRepository.save(profile);

        if (saved.isOnline() && saved.isAvailable()) {
            eventPublisher.publishEvent(new DriverOnlineEvent(saved.getId(), saved.getCity()));
        }

        return new DriverLocationResponse(saved.getId(), saved.getCurrentLat(), saved.getCurrentLng(), now);
    }

    private DriverProfile loadProfileForUser(Long userId) {
        return driverProfileRepository.findByUserIdWithDetails(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
    }

    private DriverDocument saveDocumentRow(
            DriverProfile profile,
            DriverDocumentType docType,
            DocumentStorageService.StoredDocument stored
    ) {
        DriverDocument entity = DriverDocument.builder()
                .driverProfile(profile)
                .docType(docType)
                .storedFilename(stored.storedFilename())
                .originalFilename(stored.originalFilename())
                .contentType(stored.contentType())
                .fileSizeBytes(stored.fileSizeBytes())
                .uploadedAt(Instant.now())
                .build();
        return driverDocumentRepository.save(entity);
    }

    private String normalizeAndValidateVehicleNumber(String rawVehicleNumber) {
        String normalized = rawVehicleNumber.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
        if (!INDIAN_VEHICLE_NUMBER_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "Vehicle number must be a valid Indian registration plate (e.g. DL01AB1234 or 24BH1234AB)"
            );
        }
        return normalized;
    }

    private String normalizeCity(String rawCity) {
        String trimmed = rawCity.trim().replaceAll("\\s+", " ");
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    public record DownloadedDocument(
            Resource resource,
            String contentType,
            String originalFilename,
            long fileSizeBytes
    ) {
    }
}
