package com.sam.apnasamaan.shipment;

import com.sam.apnasamaan.BaseIntegrationTest;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.CustomerRegisterRequest;
import com.sam.apnasamaan.auth.dto.LoginRequest;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentCancelRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentCreateRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentQuoteRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentQuoteResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentStatusHistoryResponse;
import com.sam.apnasamaan.shipment.entity.PackageCategory;
import com.sam.apnasamaan.shipment.entity.RouteSource;
import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentActorRole;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.repository.ShipmentRepository;
import com.sam.apnasamaan.shipment.service.ShipmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ShipmentBookingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private ShipmentService shipmentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<String> createdEmails = new ArrayList<>();

    @AfterEach
    void cleanTestUsers() {
        for (String email : createdEmails) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
        }
        createdEmails.clear();
    }

    @Test
    void quoteEndpoint_isPublicDeterministic_andWritesZeroDatabaseRows() {
        long countBefore = shipmentRepository.count();

        ShipmentQuoteRequest quoteReq = new ShipmentQuoteRequest(
                28.6315, 77.2167,
                28.4950, 77.0895,
                new BigDecimal("120.00"),
                110, 80, 90,
                PackageCategory.HOUSEHOLD,
                "Washing machine and 3 packed cartons",
                true,
                null,
                true
        );

        ResponseEntity<ShipmentQuoteResponse> quoteRes = restTemplate.postForEntity(
                "/api/v1/shipments/quote",
                quoteReq,
                ShipmentQuoteResponse.class
        );

        assertThat(quoteRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        ShipmentQuoteResponse body = quoteRes.getBody();
        assertThat(body).isNotNull();
        assertThat(body.routeSource()).isEqualTo(RouteSource.HAVERSINE_FALLBACK);
        assertThat(body.selectedVehicleCode()).isEqualTo("CARGO_AUTO");
        assertThat(body.recommendedVehicleCode()).isEqualTo("CARGO_AUTO");
        assertThat(body.baseFare()).isEqualByComparingTo("180.00");
        assertThat(body.gstApplicable()).isTrue();
        assertThat(body.gstRatePercent()).isEqualByComparingTo("18.00");
        assertThat(body.totalFare()).isEqualByComparingTo(body.subtotalFare().add(body.gstAmount()));
        assertThat(body.eligibleVehicles()).hasSize(3); // CARGO_AUTO, MINI_TRUCK, TRUCK

        // Must never create shipment rows during a quote preview
        assertThat(shipmentRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void fullBookingOwnershipAdminSearchAndPrePickupCancellationFlow_succeeds() {
        String customerAEmail = registerCustomerAndGetEmail("Customer A", "9876500001");
        String customerAToken = loginAndGetAccessToken(customerAEmail, "Pass@12345");

        String customerBEmail = registerCustomerAndGetEmail("Customer B", "9876500002");
        String customerBToken = loginAndGetAccessToken(customerBEmail, "Pass@12345");

        String adminToken = loginAndGetAccessToken(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD);

        // 1. Unauthenticated booking is rejected with 401
        ShipmentCreateRequest validCreate = buildValidCreateRequest("CARGO_AUTO", true);
        ResponseEntity<ApiErrorResponse> unauthRes = restTemplate.postForEntity(
                "/api/v1/shipments", validCreate, ApiErrorResponse.class
        );
        assertThat(unauthRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 2. Undersized vehicle choice (BIKE for 120 kg) is rejected with 400
        ShipmentCreateRequest undersizedCreate = buildValidCreateRequest("BIKE", true);
        ResponseEntity<ApiErrorResponse> undersizedRes = restTemplate.exchange(
                "/api/v1/shipments",
                HttpMethod.POST,
                new HttpEntity<>(undersizedCreate, bearerHeaders(customerAToken)),
                ApiErrorResponse.class
        );
        assertThat(undersizedRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(undersizedRes.getBody()).isNotNull();
        assertThat(undersizedRes.getBody().message()).contains("Minimum required vehicle is 'CARGO_AUTO'");

        // 3. Customer A books valid shipment -> 201 Created in PENDING_PAYMENT
        ResponseEntity<ShipmentDetailResponse> createRes = restTemplate.exchange(
                "/api/v1/shipments",
                HttpMethod.POST,
                new HttpEntity<>(validCreate, bearerHeaders(customerAToken)),
                ShipmentDetailResponse.class
        );
        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ShipmentDetailResponse created = createRes.getBody();
        assertThat(created).isNotNull();
        assertThat(created.trackingCode()).startsWith("AS-");
        assertThat(created.status()).isEqualTo(ShipmentStatus.PENDING_PAYMENT);
        assertThat(created.cancellableByCustomer()).isTrue();
        assertThat(created.vehicleTypeCode()).isEqualTo("CARGO_AUTO");
        assertThat(created.totalFare()).isGreaterThan(new BigDecimal("500.00"));

        Long shipmentId = created.id();

        // 4. Customer B cannot view or cancel Customer A's shipment (403 Forbidden)
        ResponseEntity<ApiErrorResponse> forbiddenView = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerBToken)),
                ApiErrorResponse.class
        );
        assertThat(forbiddenView.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<ApiErrorResponse> forbiddenCancel = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(new ShipmentCancelRequest("Trying unauthorized cancel"), bearerHeaders(customerBToken)),
                ApiErrorResponse.class
        );
        assertThat(forbiddenCancel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 5. Customer A sees own shipment in /my; Admin finds it via /api/v1/admin/shipments search
        ResponseEntity<Map<String, Object>> myListRes = restTemplate.exchange(
                "/api/v1/shipments/my?status=PENDING_PAYMENT",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerAToken)),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(myListRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(myListRes.getBody()).containsEntry("totalElements", 1);

        ResponseEntity<Map<String, Object>> adminSearchRes = restTemplate.exchange(
                "/api/v1/admin/shipments?status=PENDING_PAYMENT&search=" + created.trackingCode(),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminToken)),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(adminSearchRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminSearchRes.getBody()).containsEntry("totalElements", 1);

        // 6. Customer A cancels before pickup -> status becomes CANCELLED & audit history has 2 entries
        ResponseEntity<ShipmentDetailResponse> cancelRes = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ShipmentCancelRequest("Customer rescheduled house move to Monday"),
                        bearerHeaders(customerAToken)
                ),
                ShipmentDetailResponse.class
        );
        assertThat(cancelRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelRes.getBody()).isNotNull();
        assertThat(cancelRes.getBody().status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(cancelRes.getBody().cancellableByCustomer()).isFalse();
        assertThat(cancelRes.getBody().cancellationReason()).isEqualTo("Customer rescheduled house move to Monday");

        ResponseEntity<List<ShipmentStatusHistoryResponse>> historyRes = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/history",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerAToken)),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(historyRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(historyRes.getBody()).hasSize(2);
        assertThat(historyRes.getBody().get(0).toStatus()).isEqualTo(ShipmentStatus.PENDING_PAYMENT);
        assertThat(historyRes.getBody().get(1).fromStatus()).isEqualTo(ShipmentStatus.PENDING_PAYMENT);
        assertThat(historyRes.getBody().get(1).toStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void cancelEndpoint_rejectsCustomerCancellationOncePickedUp_andBlocksDriverBooking() {
        String customerEmail = registerCustomerAndGetEmail("Customer Pickup", "9876500003");
        String customerToken = loginAndGetAccessToken(customerEmail, "Pass@12345");

        // Driver user cannot call POST /api/v1/shipments (403 Forbidden)
        String driverEmail = "driver.role." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        createdEmails.add(driverEmail);
        userRepository.save(User.builder()
                .name("Driver Role User")
                .email(driverEmail)
                .phone("9876500004")
                .passwordHash(passwordEncoder.encode("Pass@12345"))
                .role(Role.DRIVER)
                .enabled(true)
                .build());
        String driverToken = loginAndGetAccessToken(driverEmail, "Pass@12345");

        ResponseEntity<ApiErrorResponse> driverCreateRes = restTemplate.exchange(
                "/api/v1/shipments",
                HttpMethod.POST,
                new HttpEntity<>(buildValidCreateRequest("CARGO_AUTO", false), bearerHeaders(driverToken)),
                ApiErrorResponse.class
        );
        assertThat(driverCreateRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Advance a booked shipment through centralized state machine to PICKED_UP
        ResponseEntity<ShipmentDetailResponse> createdRes = restTemplate.exchange(
                "/api/v1/shipments",
                HttpMethod.POST,
                new HttpEntity<>(buildValidCreateRequest("CARGO_AUTO", false), bearerHeaders(customerToken)),
                ShipmentDetailResponse.class
        );
        Long shipmentId = createdRes.getBody().id();

        Shipment entity = shipmentRepository.findByIdWithDetails(shipmentId).orElseThrow();
        shipmentService.transitionStatus(entity, ShipmentStatus.SEARCHING_DRIVER, null, ShipmentActorRole.SYSTEM, "Paid");
        shipmentService.transitionStatus(entity, ShipmentStatus.ASSIGNED, null, ShipmentActorRole.SYSTEM, "Assigned");
        shipmentService.transitionStatus(entity, ShipmentStatus.PICKED_UP, null, ShipmentActorRole.DRIVER, "Picked up");
        shipmentRepository.save(entity);

        // Customer cancellation after PICKED_UP must fail with 400 Bad Request
        ResponseEntity<ApiErrorResponse> lateCancelRes = restTemplate.exchange(
                "/api/v1/shipments/" + shipmentId + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(new ShipmentCancelRequest("Too late cancel"), bearerHeaders(customerToken)),
                ApiErrorResponse.class
        );
        assertThat(lateCancelRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(lateCancelRes.getBody()).isNotNull();
        assertThat(lateCancelRes.getBody().message()).contains("before PICKED_UP");

        // Public geocode endpoint returns 200 (empty fallback list in offline test mode) & validates min length
        ResponseEntity<List<Object>> geocodeOk = restTemplate.exchange(
                "/api/v1/maps/geocode?q=Delhi",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(geocodeOk.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<ApiErrorResponse> geocodeShort = restTemplate.getForEntity(
                "/api/v1/maps/geocode?q=x",
                ApiErrorResponse.class
        );
        assertThat(geocodeShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String registerCustomerAndGetEmail(String name, String phone) {
        String email = "cust." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        createdEmails.add(email);
        CustomerRegisterRequest req = new CustomerRegisterRequest(name, email, phone, "Pass@12345");
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/register/customer", req, AuthResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private String loginAndGetAccessToken(String email, String password) {
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, password),
                AuthResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        return res.getBody().accessToken();
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static ShipmentCreateRequest buildValidCreateRequest(String vehicleCode, boolean gst) {
        return new ShipmentCreateRequest(
                "Block A, Connaught Place, New Delhi 110001",
                "Delhi",
                28.6315,
                77.2167,
                "Rohan Sharma",
                "9876543210",
                "DLF Cyber Hub, Phase 2, Gurugram 122002",
                "Gurugram",
                28.4950,
                77.0895,
                "Priya Verma",
                "9812345678",
                "Washing machine and 3 packed cartons",
                PackageCategory.HOUSEHOLD,
                new BigDecimal("120.00"),
                110,
                80,
                90,
                true,
                vehicleCode,
                gst
        );
    }
}
