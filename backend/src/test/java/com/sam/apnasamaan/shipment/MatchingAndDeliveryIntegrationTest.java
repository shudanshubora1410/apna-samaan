package com.sam.apnasamaan.shipment;

import com.sam.apnasamaan.BaseIntegrationTest;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.CustomerRegisterRequest;
import com.sam.apnasamaan.auth.dto.LoginRequest;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.auth.security.AuthRateLimiter;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.driver.dto.DriverAvailabilityRequest;
import com.sam.apnasamaan.driver.dto.DriverLocationResponse;
import com.sam.apnasamaan.driver.dto.DriverLocationUpdateRequest;
import com.sam.apnasamaan.driver.dto.DriverProfileResponse;
import com.sam.apnasamaan.driver.dto.DriverRegistrationResponse;
import com.sam.apnasamaan.payment.config.RazorpayProperties;
import com.sam.apnasamaan.payment.dto.PaymentOrderCreateRequest;
import com.sam.apnasamaan.payment.dto.PaymentOrderResponse;
import com.sam.apnasamaan.payment.dto.PaymentResponse;
import com.sam.apnasamaan.payment.dto.PaymentVerifyRequest;
import com.sam.apnasamaan.payment.entity.PaymentStatus;
import com.sam.apnasamaan.shipment.dto.DeliveryNoteRequest;
import com.sam.apnasamaan.shipment.dto.DriverJobOfferResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentCancelRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentCreateRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentStatusHistoryResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentSummaryResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentTrackingResponse;
import com.sam.apnasamaan.shipment.entity.DriverJobOffer;
import com.sam.apnasamaan.shipment.entity.DriverJobOfferStatus;
import com.sam.apnasamaan.shipment.entity.PackageCategory;
import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.repository.DriverJobOfferRepository;
import com.sam.apnasamaan.shipment.repository.ShipmentRepository;
import com.sam.apnasamaan.shipment.service.DriverMatchingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class MatchingAndDeliveryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private DriverJobOfferRepository offerRepository;

    @Autowired
    private DriverMatchingService driverMatchingService;

    @Autowired
    private RazorpayProperties razorpayProperties;

    @Autowired
    private AuthRateLimiter rateLimiter;

    private final List<String> createdEmails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rateLimiter.clearAll();
        reset(javaMailSender);
    }

    @AfterEach
    void cleanUp() {
        for (String email : createdEmails) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
        }
        createdEmails.clear();
        rateLimiter.clearAll();
    }

    @Test
    void haversineSequentialOffers_declineAdvancesToNextDriver_acceptAndFullDeliveryFlowReleasesDriver() {
        String adminToken = login(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD);
        String uniqueCity = "MatchCity" + UUID.randomUUID().toString().substring(0, 6);

        // Register & approve TWO MINI_TRUCK drivers in uniqueCity:
        // Driver Far (~14 km away) and Driver Near (~0.5 km away from pickup 28.6315, 77.2167)
        TestDriver farDriver = registerApproveAndBringOnline(
                adminToken, "Vikram Far", "MINI_TRUCK", uniqueCity, 28.7041, 77.1025
        );
        TestDriver nearDriver = registerApproveAndBringOnline(
                adminToken, "Ramesh Near", "MINI_TRUCK", uniqueCity, 28.6350, 77.2200
        );

        String customerEmail = registerCustomer("Ananya Gupta", "9876520001");
        String customerToken = login(customerEmail, "Pass@12345");
        String strangerEmail = registerCustomer("Stranger User", "9876520002");
        String strangerToken = login(strangerEmail, "Pass@12345");

        // Customer books shipment in uniqueCity and pays via Razorpay verify
        ShipmentDetailResponse booked = bookAndPayShipment(customerToken, uniqueCity, "MINI_TRUCK");
        Long shipmentId = booked.id();

        // 1. Sequential Haversine rule: Offer #1 goes ONLY to nearDriver (~0.51 km), NOT farDriver
        List<DriverJobOfferResponse> nearOffers = getPendingOffers(nearDriver.token());
        List<DriverJobOfferResponse> farOffersBefore = getPendingOffers(farDriver.token());

        assertThat(nearOffers).hasSize(1);
        assertThat(farOffersBefore).isEmpty();

        DriverJobOfferResponse offer1 = nearOffers.get(0);
        assertThat(offer1.shipmentId()).isEqualTo(shipmentId);
        assertThat(offer1.sequenceNumber()).isEqualTo(1);
        assertThat(offer1.distanceToPickupKm()).isLessThan(new BigDecimal("1.00"));
        assertThat(offer1.remainingSeconds()).isGreaterThan(0L);

        // Wrong driver cannot accept nearDriver's offer -> 403 Forbidden
        ResponseEntity<ApiErrorResponse> wrongDriverAccept = restTemplate.exchange(
                "/api/v1/driver/offers/" + offer1.offerId() + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(null, bearerHeaders(farDriver.token())),
                ApiErrorResponse.class
        );
        assertThat(wrongDriverAccept.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 2. nearDriver declines Offer #1 -> Offer #2 is immediately dispatched to farDriver!
        ResponseEntity<String> declineRes = restTemplate.exchange(
                "/api/v1/driver/offers/" + offer1.offerId() + "/decline",
                HttpMethod.POST,
                new HttpEntity<>(null, bearerHeaders(nearDriver.token())),
                String.class
        );
        assertThat(declineRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getPendingOffers(nearDriver.token())).isEmpty();

        List<DriverJobOfferResponse> farOffersAfter = getPendingOffers(farDriver.token());
        assertThat(farOffersAfter).hasSize(1);
        DriverJobOfferResponse offer2 = farOffersAfter.get(0);
        assertThat(offer2.sequenceNumber()).isEqualTo(2);
        assertThat(offer2.distanceToPickupKm()).isGreaterThan(new BigDecimal("10.00"));

        // 3. farDriver accepts Offer #2 -> SEARCHING_DRIVER -> ASSIGNED + driver.available=false + ASSIGNED email
        ResponseEntity<ShipmentDetailResponse> acceptRes = restTemplate.exchange(
                "/api/v1/driver/offers/" + offer2.offerId() + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(null, bearerHeaders(farDriver.token())),
                ShipmentDetailResponse.class
        );
        assertThat(acceptRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acceptRes.getBody()).isNotNull();
        assertThat(acceptRes.getBody().status()).isEqualTo(ShipmentStatus.ASSIGNED);
        assertThat(acceptRes.getBody().assignedDriverProfileId()).isEqualTo(farDriver.profileId());

        DriverProfileResponse farProfileWhileAssigned = getDriverProfile(farDriver.token());
        assertThat(farProfileWhileAssigned.available()).isFalse();

        // Driver CANNOT switch offline while on an active delivery -> 400 Bad Request
        ResponseEntity<ApiErrorResponse> offlineBlocked = restTemplate.exchange(
                "/api/v1/driver/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(new DriverAvailabilityRequest(false), bearerHeaders(farDriver.token())),
                ApiErrorResponse.class
        );
        assertThat(offlineBlocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 4. Live GPS update (~10s driver push) & live customer tracking (~5s poll)
        ResponseEntity<DriverLocationResponse> locUpdate = restTemplate.exchange(
                "/api/v1/driver/location",
                HttpMethod.PUT,
                new HttpEntity<>(new DriverLocationUpdateRequest(28.6300, 77.2180), bearerHeaders(farDriver.token())),
                DriverLocationResponse.class
        );
        assertThat(locUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Unrelated customer cannot spy on tracking -> 403 Forbidden
        ResponseEntity<ApiErrorResponse> strangerTrack = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/tracking",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(strangerToken)),
                ApiErrorResponse.class
        );
        assertThat(strangerTrack.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<ShipmentTrackingResponse> liveTrack = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/tracking",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerToken)),
                ShipmentTrackingResponse.class
        );
        assertThat(liveTrack.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveTrack.getBody()).isNotNull();
        assertThat(liveTrack.getBody().driverLat()).isEqualTo(28.6300);
        assertThat(liveTrack.getBody().driverLng()).isEqualTo(77.2180);
        assertThat(liveTrack.getBody().distanceToTargetKm()).isNotNull();

        // 5. Invalid state jump (ASSIGNED -> DELIVERED directly) is rejected with 400 Bad Request
        ResponseEntity<ApiErrorResponse> skipToDeliver = restTemplate.exchange(
                "/api/v1/driver/shipments/" + shipmentId + "/deliver",
                HttpMethod.POST,
                new HttpEntity<>(new DeliveryNoteRequest("Trying to skip pickup"), bearerHeaders(farDriver.token())),
                ApiErrorResponse.class
        );
        assertThat(skipToDeliver.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Non-assigned driver cannot mark pickup -> 403 Forbidden
        ResponseEntity<ApiErrorResponse> wrongDriverPickup = restTemplate.exchange(
                "/api/v1/driver/shipments/" + shipmentId + "/pickup",
                HttpMethod.POST,
                new HttpEntity<>(new DeliveryNoteRequest("Hijack"), bearerHeaders(nearDriver.token())),
                ApiErrorResponse.class
        );
        assertThat(wrongDriverPickup.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 6. Valid progression: ASSIGNED -> PICKED_UP -> IN_TRANSIT -> DELIVERED
        ResponseEntity<ShipmentDetailResponse> pickedUpRes = restTemplate.exchange(
                "/api/v1/driver/shipments/" + shipmentId + "/pickup",
                HttpMethod.POST,
                new HttpEntity<>(new DeliveryNoteRequest("Loaded household boxes safely"), bearerHeaders(farDriver.token())),
                ShipmentDetailResponse.class
        );
        assertThat(pickedUpRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pickedUpRes.getBody().status()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(pickedUpRes.getBody().pickedUpAt()).isNotNull();

        // Active deliveries endpoint returns the shipment while PICKED_UP
        ResponseEntity<List<ShipmentDetailResponse>> activeList = restTemplate.exchange(
                "/api/v1/driver/shipments/active",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(farDriver.token())),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(activeList.getBody()).hasSize(1);

        ResponseEntity<ShipmentDetailResponse> inTransitRes = restTemplate.exchange(
                "/api/v1/driver/shipments/" + shipmentId + "/in-transit",
                HttpMethod.POST,
                new HttpEntity<>(new DeliveryNoteRequest("Crossing DND Flyway"), bearerHeaders(farDriver.token())),
                ShipmentDetailResponse.class
        );
        assertThat(inTransitRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inTransitRes.getBody().status()).isEqualTo(ShipmentStatus.IN_TRANSIT);

        ResponseEntity<ShipmentDetailResponse> deliveredRes = restTemplate.exchange(
                "/api/v1/driver/shipments/" + shipmentId + "/deliver",
                HttpMethod.POST,
                new HttpEntity<>(new DeliveryNoteRequest("Handed over to recipient"), bearerHeaders(farDriver.token())),
                ShipmentDetailResponse.class
        );
        assertThat(deliveredRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deliveredRes.getBody().status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(deliveredRes.getBody().deliveredAt()).isNotNull();

        // 7. Driver is released back to available=true and active deliveries is now empty
        DriverProfileResponse farProfileAfterDelivery = getDriverProfile(farDriver.token());
        assertThat(farProfileAfterDelivery.available()).isTrue();

        ResponseEntity<PagedResponse<ShipmentSummaryResponse>> driverHistory = restTemplate.exchange(
                "/api/v1/driver/shipments?status=DELIVERED",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(farDriver.token())),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(driverHistory.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(driverHistory.getBody().items()).hasSize(1);

        // Post-delivery privacy: tracking endpoint masks personal driver coordinates once DELIVERED
        ResponseEntity<ShipmentTrackingResponse> postDeliveryTrack = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/tracking",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerToken)),
                ShipmentTrackingResponse.class
        );
        assertThat(postDeliveryTrack.getBody().status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(postDeliveryTrack.getBody().driverLat()).isNull();
        assertThat(postDeliveryTrack.getBody().driverLng()).isNull();

        // Both ASSIGNED and DELIVERED emails were sent
        verify(javaMailSender, atLeast(2)).send(any(SimpleMailMessage.class));

        // Chronological status audit trail has all 6 transitions
        ResponseEntity<List<ShipmentStatusHistoryResponse>> historyRes = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/history",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerToken)),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(historyRes.getBody())
                .extracting(ShipmentStatusHistoryResponse::toStatus)
                .containsExactly(
                        ShipmentStatus.PENDING_PAYMENT,
                        ShipmentStatus.SEARCHING_DRIVER,
                        ShipmentStatus.ASSIGNED,
                        ShipmentStatus.PICKED_UP,
                        ShipmentStatus.IN_TRANSIT,
                        ShipmentStatus.DELIVERED
                );
    }

    @Test
    void offerTimeoutAndSearchTimeout_expiresOffer_marksShipmentFailed_andIssuesAutomaticFullRefund() {
        String adminToken = login(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD);
        String timeoutCity = "TimeoutCity" + UUID.randomUUID().toString().substring(0, 6);

        TestDriver silentDriver = registerApproveAndBringOnline(
                adminToken, "Silent Driver", "BIKE", timeoutCity, 26.9124, 75.7873
        );

        String customerEmail = registerCustomer("Timeout Customer", "9876520010");
        String customerToken = login(customerEmail, "Pass@12345");

        ShipmentDetailResponse booked = bookAndPayShipment(customerToken, timeoutCity, "BIKE");
        Long shipmentId = booked.id();

        List<DriverJobOffer> initialOffers = offerRepository.findPendingOffersByShipmentId(shipmentId);
        assertThat(initialOffers).hasSize(1);
        DriverJobOffer offer1 = initialOffers.get(0);
        assertThat(offer1.getDriverProfile().getId()).isEqualTo(silentDriver.profileId());

        // Simulate 60s offer expiring + overall 180s search window elapsing with no remaining drivers
        offer1.setExpiresAt(Instant.now().minusSeconds(5));
        offerRepository.save(offer1);

        Shipment shipmentEntity = shipmentRepository.findById(shipmentId).orElseThrow();
        shipmentEntity.setSearchStartedAt(Instant.now().minusSeconds(300));
        shipmentRepository.save(shipmentEntity);

        driverMatchingService.processTimeoutsAndPendingSearches();

        DriverJobOffer reloadedOffer = offerRepository.findById(offer1.getId()).orElseThrow();
        assertThat(reloadedOffer.getStatus()).isEqualTo(DriverJobOfferStatus.EXPIRED);

        // Shipment is now FAILED and customer's captured payment is automatically REFUNDED
        ResponseEntity<ShipmentDetailResponse> failedDetail = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerToken)),
                ShipmentDetailResponse.class
        );
        assertThat(failedDetail.getBody().status()).isEqualTo(ShipmentStatus.FAILED);

        ResponseEntity<PaymentResponse> refundedPayment = restTemplate.exchange(
                "/api/v1/payments/shipment/" + shipmentId,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerToken)),
                PaymentResponse.class
        );
        assertThat(refundedPayment.getBody().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundedPayment.getBody().razorpayRefundId()).startsWith("rfnd_sim_");
    }

    @Test
    void customerCancelWhileAssigned_releasesDriverAvailabilityAndRefundsPayment() {
        String adminToken = login(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD);
        String cancelCity = "CancelCity" + UUID.randomUUID().toString().substring(0, 6);

        TestDriver driver = registerApproveAndBringOnline(
                adminToken, "Manoj Auto", "CARGO_AUTO", cancelCity, 19.0760, 72.8777
        );

        String customerEmail = registerCustomer("Cancel Customer", "9876520020");
        String customerToken = login(customerEmail, "Pass@12345");

        ShipmentDetailResponse booked = bookAndPayShipment(customerToken, cancelCity, "CARGO_AUTO");
        List<DriverJobOfferResponse> pending = getPendingOffers(driver.token());
        assertThat(pending).hasSize(1);

        // Driver accepts -> available becomes false, status becomes ASSIGNED
        restTemplate.exchange(
                "/api/v1/driver/offers/" + pending.get(0).offerId() + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(null, bearerHeaders(driver.token())),
                ShipmentDetailResponse.class
        );
        assertThat(getDriverProfile(driver.token()).available()).isFalse();

        // Customer cancels before pickup -> shipment CANCELLED, payment REFUNDED, driver available=true again!
        ResponseEntity<ShipmentDetailResponse> cancelRes = restTemplate.exchange(
                "/api/v1/shipments/" + booked.id() + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(new ShipmentCancelRequest("Sender postponed dispatch"), bearerHeaders(customerToken)),
                ShipmentDetailResponse.class
        );
        assertThat(cancelRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelRes.getBody().status()).isEqualTo(ShipmentStatus.CANCELLED);

        assertThat(getDriverProfile(driver.token()).available()).isTrue();

        ResponseEntity<PaymentResponse> paymentRes = restTemplate.exchange(
                "/api/v1/payments/shipment/" + booked.id(),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerToken)),
                PaymentResponse.class
        );
        assertThat(paymentRes.getBody().status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void idleDriverGoingOfflineWithPendingOffer_cancelsOfferAndAdvancesToNextDriver_andLateDriverMatchWorks() {
        String adminToken = login(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD);
        String dynamicCity = "LateMatch" + UUID.randomUUID().toString().substring(0, 5);

        String customerEmail = registerCustomer("Late Match Customer", "9876520030");
        String customerToken = login(customerEmail, "Pass@12345");

        // 1. Customer pays BEFORE any driver is online in dynamicCity -> stays in SEARCHING_DRIVER
        ShipmentDetailResponse booked = bookAndPayShipment(customerToken, dynamicCity, "BIKE");

        // 2. Driver #1 comes online AFTER payment -> DriverOnlineEvent immediately dispatches Offer #1!
        TestDriver driver1 = registerApproveAndBringOnline(
                adminToken, "First Bike", "BIKE", dynamicCity, 28.6320, 77.2170
        );
        TestDriver driver2 = registerApproveAndBringOnline(
                adminToken, "Second Bike", "BIKE", dynamicCity, 28.6400, 77.2250
        );

        assertThat(getPendingOffers(driver1.token())).hasSize(1);
        assertThat(getPendingOffers(driver2.token())).isEmpty();

        // 3. Driver #1 switches offline without responding -> Offer #1 cancelled & Offer #2 goes to Driver #2!
        ResponseEntity<DriverProfileResponse> offlineRes = restTemplate.exchange(
                "/api/v1/driver/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(new DriverAvailabilityRequest(false), bearerHeaders(driver1.token())),
                DriverProfileResponse.class
        );
        assertThat(offlineRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getPendingOffers(driver1.token())).isEmpty();

        List<DriverJobOfferResponse> driver2Offers = getPendingOffers(driver2.token());
        assertThat(driver2Offers).hasSize(1);
        assertThat(driver2Offers.get(0).shipmentId()).isEqualTo(booked.id());
        assertThat(driver2Offers.get(0).sequenceNumber()).isEqualTo(2);
    }

    // --- Test Helpers ---

    private TestDriver registerApproveAndBringOnline(
            String adminToken,
            String name,
            String vehicleTypeCode,
            String city,
            double lat,
            double lng
    ) {
        String email = "driver." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        createdEmails.add(email);
        String password = "Driver#" + UUID.randomUUID().toString().substring(0, 8);
        String plate = uniqueVehicleNumber("DL09");

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("name", name);
        form.add("email", email);
        form.add("phone", "9811" + String.format("%06d", new Random().nextInt(999999)));
        form.add("password", password);
        form.add("vehicleTypeCode", vehicleTypeCode);
        form.add("vehicleNumber", plate);
        form.add("city", city);
        form.add("drivingLicence", namedPdf("dl.pdf"));
        form.add("vehicleRc", namedPdf("rc.pdf"));

        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<DriverRegistrationResponse> regRes = restTemplate.postForEntity(
                "/api/v1/auth/register/driver",
                new HttpEntity<>(form, multipartHeaders),
                DriverRegistrationResponse.class
        );
        assertThat(regRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long profileId = regRes.getBody().driverProfile().driverProfileId();
        String driverToken = regRes.getBody().auth().accessToken();

        // Admin approves driver
        ResponseEntity<DriverProfileResponse> approveRes = restTemplate.exchange(
                "/api/v1/admin/drivers/" + profileId + "/approve",
                HttpMethod.PATCH,
                new HttpEntity<>(null, bearerHeaders(adminToken)),
                DriverProfileResponse.class
        );
        assertThat(approveRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Driver reports GPS coordinates and toggles ONLINE
        restTemplate.exchange(
                "/api/v1/driver/location",
                HttpMethod.PUT,
                new HttpEntity<>(new DriverLocationUpdateRequest(lat, lng), bearerHeaders(driverToken)),
                DriverLocationResponse.class
        );
        ResponseEntity<DriverProfileResponse> onlineRes = restTemplate.exchange(
                "/api/v1/driver/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(new DriverAvailabilityRequest(true), bearerHeaders(driverToken)),
                DriverProfileResponse.class
        );
        assertThat(onlineRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(onlineRes.getBody().online()).isTrue();

        return new TestDriver(profileId, email, driverToken);
    }

    private ShipmentDetailResponse bookAndPayShipment(
            String customerToken,
            String pickupCity,
            String vehicleTypeCode
    ) {
        BigDecimal weight = switch (vehicleTypeCode) {
            case "BIKE" -> new BigDecimal("5.00");
            case "CARGO_AUTO" -> new BigDecimal("120.00");
            default -> new BigDecimal("350.00");
        };
        int dimCm = "BIKE".equals(vehicleTypeCode) ? 30 : 60;

        ShipmentCreateRequest createReq = new ShipmentCreateRequest(
                "Connaught Place, Inner Circle",
                pickupCity,
                28.6315,
                77.2167,
                "Aarav Sender",
                "9876543210",
                "Sector 62, Electronic City",
                "Noida",
                28.6270,
                77.3650,
                "Priya Receiver",
                "9876543211",
                "Packed commercial consignment",
                PackageCategory.HOUSEHOLD,
                weight,
                dimCm,
                dimCm,
                dimCm,
                false,
                vehicleTypeCode,
                true
        );

        ResponseEntity<ShipmentDetailResponse> createRes = restTemplate.exchange(
                "/api/v1/shipments",
                HttpMethod.POST,
                new HttpEntity<>(createReq, bearerHeaders(customerToken)),
                ShipmentDetailResponse.class
        );
        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ShipmentDetailResponse shipment = createRes.getBody();

        ResponseEntity<PaymentOrderResponse> orderRes = restTemplate.exchange(
                "/api/v1/payments/orders",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentOrderCreateRequest(shipment.id()), bearerHeaders(customerToken)),
                PaymentOrderResponse.class
        );
        assertThat(orderRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        String orderId = orderRes.getBody().razorpayOrderId();
        String payId = "pay_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sig = hmacSha256Hex(orderId + "|" + payId, razorpayProperties.effectiveKeySecret());

        ResponseEntity<PaymentResponse> verifyRes = restTemplate.exchange(
                "/api/v1/payments/verify",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentVerifyRequest(shipment.id(), orderId, payId, sig), bearerHeaders(customerToken)),
                PaymentResponse.class
        );
        assertThat(verifyRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verifyRes.getBody().shipmentStatus()).isEqualTo(ShipmentStatus.SEARCHING_DRIVER);

        return shipment;
    }

    private List<DriverJobOfferResponse> getPendingOffers(String driverToken) {
        ResponseEntity<List<DriverJobOfferResponse>> res = restTemplate.exchange(
                "/api/v1/driver/offers/pending",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(driverToken)),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private DriverProfileResponse getDriverProfile(String driverToken) {
        ResponseEntity<DriverProfileResponse> res = restTemplate.exchange(
                "/api/v1/driver/me",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(driverToken)),
                DriverProfileResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private String registerCustomer(String name, String phone) {
        String email = "cust." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        createdEmails.add(email);
        CustomerRegisterRequest req = new CustomerRegisterRequest(name, email, phone, "Pass@12345");
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/v1/auth/register/customer", req, AuthResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private String login(String email, String password) {
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                AuthResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody().accessToken();
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static ByteArrayResource namedPdf(String filename) {
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF".getBytes(StandardCharsets.UTF_8);
        return new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static String uniqueVehicleNumber(String prefix) {
        Random rnd = new Random();
        char c1 = (char) ('A' + rnd.nextInt(26));
        char c2 = (char) ('A' + rnd.nextInt(26));
        int digits = 1000 + rnd.nextInt(9000);
        return prefix + c1 + c2 + digits;
    }

    private static String hmacSha256Hex(String message, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record TestDriver(Long profileId, String email, String token) {
    }
}
