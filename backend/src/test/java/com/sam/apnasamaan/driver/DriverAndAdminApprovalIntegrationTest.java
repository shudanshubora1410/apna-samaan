package com.sam.apnasamaan.driver;

import com.sam.apnasamaan.BaseIntegrationTest;
import com.sam.apnasamaan.admin.dto.DriverRejectRequest;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.CustomerRegisterRequest;
import com.sam.apnasamaan.auth.dto.LoginRequest;
import com.sam.apnasamaan.auth.dto.RefreshTokenRequest;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.auth.security.AuthRateLimiter;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.driver.dto.DriverAvailabilityRequest;
import com.sam.apnasamaan.driver.dto.DriverDocumentResponse;
import com.sam.apnasamaan.driver.dto.DriverProfileResponse;
import com.sam.apnasamaan.driver.dto.DriverRegistrationResponse;
import com.sam.apnasamaan.driver.entity.DriverApprovalStatus;
import com.sam.apnasamaan.driver.entity.DriverDocumentType;
import com.sam.apnasamaan.shipment.dto.VehicleTypeResponse;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DriverAndAdminApprovalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthRateLimiter rateLimiter;

    private final List<String> createdTestEmails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rateLimiter.clearAll();
    }

    @AfterEach
    void cleanUp() {
        for (String email : createdTestEmails) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
        }
        createdTestEmails.clear();
        rateLimiter.clearAll();
    }

    @Test
    void vehicleTypesEndpoint_returnsFourSeededIndianTiers() {
        ResponseEntity<List<VehicleTypeResponse>> response = restTemplate.exchange(
                "/api/v1/vehicle-types",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(4);
        assertThat(response.getBody())
                .extracting(VehicleTypeResponse::code)
                .containsExactly("BIKE", "CARGO_AUTO", "MINI_TRUCK", "TRUCK");
    }

    @Test
    void driverRegistration_rejectsSpoofedFilesAndDuplicatePlates() {
        String email = uniqueEmail("spoof.driver");
        String plate = uniqueVehicleNumber("DL01");

        // Forged HTML disguised as .jpg -> 400 Bad Request, zero DB user created
        MultiValueMap<String, Object> badForm = buildDriverMultipartForm(
                "Suresh Yadav",
                email,
                "9811001100",
                "Driver#" + UUID.randomUUID().toString().substring(0, 10),
                "CARGO_AUTO",
                plate,
                "Delhi",
                namedResource("forged.jpg", "<html>not an image</html>".getBytes(StandardCharsets.UTF_8)),
                namedResource("rc.pdf", samplePdfBytes())
        );

        ResponseEntity<ApiErrorResponse> spoofRes = restTemplate.postForEntity(
                "/api/v1/auth/register/driver",
                multipartEntity(null, badForm),
                ApiErrorResponse.class
        );
        assertThat(spoofRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userRepository.existsByEmailIgnoreCase(email)).isFalse();

        // Register valid driver -> 201 Created, then try duplicate vehicle number -> 409 Conflict
        registerValidDriver("Suresh Yadav", email, "CARGO_AUTO", plate, "Delhi");

        String secondEmail = uniqueEmail("dup.plate");
        MultiValueMap<String, Object> dupPlateForm = buildDriverMultipartForm(
                "Another Driver",
                secondEmail,
                "9822002200",
                "Driver#" + UUID.randomUUID().toString().substring(0, 10),
                "CARGO_AUTO",
                plate.toLowerCase(),
                "Delhi",
                namedResource("dl.pdf", samplePdfBytes()),
                namedResource("rc.png", samplePngBytes())
        );

        ResponseEntity<ApiErrorResponse> conflictRes = restTemplate.postForEntity(
                "/api/v1/auth/register/driver",
                multipartEntity(null, dupPlateForm),
                ApiErrorResponse.class
        );
        assertThat(conflictRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fullDriverAndAdminApprovalLifecycle_enforcesDocumentPrivacy_onlineRules_andDeactivation() {
        String pass = "Driver#" + UUID.randomUUID().toString().substring(0, 10);
        String emailA = uniqueEmail("rajesh");
        String plateA = uniqueVehicleNumber("DL02");

        DriverRegistrationResponse driverA = registerValidDriver("Rajesh Kumar", emailA, "MINI_TRUCK", plateA, "Delhi");
        DriverRegistrationResponse driverB = registerValidDriver(
                "Manoj Tiwari", uniqueEmail("manoj"), "BIKE", uniqueVehicleNumber("MH12"), "Mumbai"
        );
        AuthResponse customer = registerCustomer("Ananya Gupta", uniqueEmail("ananya"), pass);
        AuthResponse admin = loginAdmin();

        Long driverAProfileId = driverA.driverProfile().driverProfileId();
        assertThat(driverA.driverProfile().approvalStatus()).isEqualTo(DriverApprovalStatus.PENDING);
        assertThat(driverA.driverProfile().online()).isFalse();
        assertThat(driverA.driverProfile().documents()).hasSize(2);

        Long driverADocId = driverA.driverProfile().documents().stream()
                .filter(d -> d.docType() == DriverDocumentType.DRIVING_LICENCE)
                .findFirst().orElseThrow().id();

        // 1. PENDING driver cannot go ONLINE -> 403 Forbidden
        ResponseEntity<ApiErrorResponse> pendingOnlineAttempt = restTemplate.exchange(
                "/api/v1/driver/availability",
                HttpMethod.PATCH,
                bearerJsonEntity(driverA.auth().accessToken(), new DriverAvailabilityRequest(true)),
                ApiErrorResponse.class
        );
        assertThat(pendingOnlineAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 2. Document endpoint privacy: Unauth -> 401, Customer -> 403, Driver B -> 403, Owner Driver A -> 200, Admin -> 200
        assertThat(restTemplate.getForEntity(
                "/api/v1/driver/documents/" + driverADocId, byte[].class
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(restTemplate.exchange(
                "/api/v1/driver/documents/" + driverADocId,
                HttpMethod.GET,
                bearerJsonEntity(customer.accessToken(), null),
                ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(restTemplate.exchange(
                "/api/v1/driver/documents/" + driverADocId,
                HttpMethod.GET,
                bearerJsonEntity(driverB.auth().accessToken(), null),
                ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<byte[]> ownerDownload = restTemplate.exchange(
                "/api/v1/driver/documents/" + driverADocId,
                HttpMethod.GET,
                bearerJsonEntity(driverA.auth().accessToken(), null),
                byte[].class
        );
        assertThat(ownerDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerDownload.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(ownerDownload.getBody()).isEqualTo(samplePdfBytes());

        ResponseEntity<byte[]> adminDownload = restTemplate.exchange(
                "/api/v1/driver/documents/" + driverADocId,
                HttpMethod.GET,
                bearerJsonEntity(admin.accessToken(), null),
                byte[].class
        );
        assertThat(adminDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminDownload.getBody()).isEqualTo(samplePdfBytes());

        // 3. Non-admin cannot access /api/v1/admin/drivers -> 403 Forbidden
        assertThat(restTemplate.exchange(
                "/api/v1/admin/drivers",
                HttpMethod.GET,
                bearerJsonEntity(driverA.auth().accessToken(), null),
                ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 4. Admin searches PENDING drivers by plate -> finds Driver A
        ResponseEntity<PagedResponse<DriverProfileResponse>> adminList = restTemplate.exchange(
                "/api/v1/admin/drivers?status=PENDING&search=" + plateA + "&page=0&size=10",
                HttpMethod.GET,
                bearerJsonEntity(admin.accessToken(), null),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(adminList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminList.getBody()).isNotNull();
        assertThat(adminList.getBody().items()).hasSize(1);
        assertThat(adminList.getBody().items().get(0).driverProfileId()).isEqualTo(driverAProfileId);

        // 5. Admin REJECTS Driver A with reason -> Driver A sees REJECTED + reason
        ResponseEntity<DriverProfileResponse> rejectedRes = restTemplate.exchange(
                "/api/v1/admin/drivers/" + driverAProfileId + "/reject",
                HttpMethod.PATCH,
                bearerJsonEntity(admin.accessToken(), new DriverRejectRequest("RC image is cropped at the bottom")),
                DriverProfileResponse.class
        );
        assertThat(rejectedRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejectedRes.getBody().approvalStatus()).isEqualTo(DriverApprovalStatus.REJECTED);
        assertThat(rejectedRes.getBody().rejectionReason()).isEqualTo("RC image is cropped at the bottom");

        // 6. Driver A replaces VEHICLE_RC with a clear JPEG -> status automatically resets to PENDING!
        MultiValueMap<String, Object> replaceRcBody = new LinkedMultiValueMap<>();
        replaceRcBody.add("file", namedResource("clear_rc.jpg", sampleJpegBytes()));

        ResponseEntity<DriverDocumentResponse> replacedDoc = restTemplate.exchange(
                "/api/v1/driver/documents/VEHICLE_RC",
                HttpMethod.PUT,
                multipartEntity(driverA.auth().accessToken(), replaceRcBody),
                DriverDocumentResponse.class
        );
        assertThat(replacedDoc.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replacedDoc.getBody().contentType()).isEqualTo(MediaType.IMAGE_JPEG_VALUE);

        ResponseEntity<DriverProfileResponse> afterReupload = restTemplate.exchange(
                "/api/v1/driver/me",
                HttpMethod.GET,
                bearerJsonEntity(driverA.auth().accessToken(), null),
                DriverProfileResponse.class
        );
        assertThat(afterReupload.getBody().approvalStatus()).isEqualTo(DriverApprovalStatus.PENDING);
        assertThat(afterReupload.getBody().rejectionReason()).isNull();

        // 7. Admin APPROVES Driver A -> Driver A can now toggle ONLINE and OFFLINE
        ResponseEntity<DriverProfileResponse> approvedRes = restTemplate.exchange(
                "/api/v1/admin/drivers/" + driverAProfileId + "/approve",
                HttpMethod.PATCH,
                bearerJsonEntity(admin.accessToken(), null),
                DriverProfileResponse.class
        );
        assertThat(approvedRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approvedRes.getBody().approvalStatus()).isEqualTo(DriverApprovalStatus.APPROVED);

        ResponseEntity<DriverProfileResponse> onlineRes = restTemplate.exchange(
                "/api/v1/driver/availability",
                HttpMethod.PATCH,
                bearerJsonEntity(driverA.auth().accessToken(), new DriverAvailabilityRequest(true)),
                DriverProfileResponse.class
        );
        assertThat(onlineRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(onlineRes.getBody().online()).isTrue();

        ResponseEntity<DriverProfileResponse> offlineRes = restTemplate.exchange(
                "/api/v1/driver/availability",
                HttpMethod.PATCH,
                bearerJsonEntity(driverA.auth().accessToken(), new DriverAvailabilityRequest(false)),
                DriverProfileResponse.class
        );
        assertThat(offlineRes.getBody().online()).isFalse();

        // 8. Admin DEACTIVATES Driver A -> forced offline, JWT & refresh token immediately blocked
        ResponseEntity<DriverProfileResponse> deactivatedRes = restTemplate.exchange(
                "/api/v1/admin/drivers/" + driverAProfileId + "/deactivate",
                HttpMethod.PATCH,
                bearerJsonEntity(admin.accessToken(), null),
                DriverProfileResponse.class
        );
        assertThat(deactivatedRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deactivatedRes.getBody().approvalStatus()).isEqualTo(DriverApprovalStatus.DEACTIVATED);
        assertThat(deactivatedRes.getBody().online()).isFalse();
        assertThat(deactivatedRes.getBody().accountEnabled()).isFalse();

        assertThat(restTemplate.exchange(
                "/api/v1/driver/me",
                HttpMethod.GET,
                bearerJsonEntity(driverA.auth().accessToken(), null),
                ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                new RefreshTokenRequest(driverA.auth().refreshToken()),
                ApiErrorResponse.class
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private DriverRegistrationResponse registerValidDriver(
            String name,
            String email,
            String vehicleCode,
            String vehicleNumber,
            String city
    ) {
        MultiValueMap<String, Object> body = buildDriverMultipartForm(
                name,
                email,
                "9876543210",
                "Driver#" + UUID.randomUUID().toString().substring(0, 10),
                vehicleCode,
                vehicleNumber,
                city,
                namedResource("driving_licence.pdf", samplePdfBytes()),
                namedResource("vehicle_rc.png", samplePngBytes())
        );

        ResponseEntity<DriverRegistrationResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/register/driver",
                multipartEntity(null, body),
                DriverRegistrationResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    private MultiValueMap<String, Object> buildDriverMultipartForm(
            String name,
            String email,
            String phone,
            String password,
            String vehicleTypeCode,
            String vehicleNumber,
            String city,
            ByteArrayResource drivingLicence,
            ByteArrayResource vehicleRc
    ) {
        MultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
        map.add("name", name);
        map.add("email", email);
        map.add("phone", phone);
        map.add("password", password);
        map.add("vehicleTypeCode", vehicleTypeCode);
        map.add("vehicleNumber", vehicleNumber);
        map.add("city", city);
        map.add("drivingLicence", drivingLicence);
        map.add("vehicleRc", vehicleRc);
        return map;
    }

    private AuthResponse registerCustomer(String name, String email, String password) {
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/register/customer",
                new CustomerRegisterRequest(name, email, "9812345678", password),
                AuthResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private AuthResponse loginAdmin() {
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD),
                AuthResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private String uniqueEmail(String prefix) {
        String email = prefix + "." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        createdTestEmails.add(email);
        return email;
    }

    private String uniqueVehicleNumber(String stateRto) {
        int digits = 1000 + new Random().nextInt(8999);
        char c1 = (char) ('A' + new Random().nextInt(26));
        char c2 = (char) ('A' + new Random().nextInt(26));
        return stateRto + c1 + c2 + digits;
    }

    private byte[] samplePdfBytes() {
        return "%PDF-1.7 Apna Samaan verified driving licence test file".getBytes(StandardCharsets.UTF_8);
    }

    private byte[] samplePngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03, 0x04};
    }

    private byte[] sampleJpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46};
    }

    private ByteArrayResource namedResource(String filename, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartEntity(
            String bearerToken,
            MultiValueMap<String, Object> body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return new HttpEntity<>(body, headers);
    }

    private <T> HttpEntity<T> bearerJsonEntity(String bearerToken, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        return new HttpEntity<>(body, headers);
    }
}
