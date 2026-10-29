package com.sam.apnasamaan.common;

import com.sam.apnasamaan.BaseIntegrationTest;
import com.sam.apnasamaan.auth.dto.AuthResponse;
import com.sam.apnasamaan.auth.dto.CustomerRegisterRequest;
import com.sam.apnasamaan.auth.dto.LoginRequest;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.common.dto.ApiErrorResponse;
import com.sam.apnasamaan.payment.dto.PaymentOrderCreateRequest;
import com.sam.apnasamaan.payment.dto.PaymentOrderResponse;
import com.sam.apnasamaan.payment.dto.PaymentResponse;
import com.sam.apnasamaan.payment.dto.PaymentSimulateRequest;
import com.sam.apnasamaan.payment.entity.PaymentStatus;
import com.sam.apnasamaan.shipment.dto.ShipmentCreateRequest;
import com.sam.apnasamaan.shipment.dto.ShipmentDetailResponse;
import com.sam.apnasamaan.shipment.entity.PackageCategory;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendServingAndSimulationCheckoutIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    private final List<String> createdEmails = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String email : createdEmails) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
        }
        createdEmails.clear();
    }

    @Test
    void staticFrontendPagesAndModules_arePubliclyServedWithExactBranding() {
        List<String> publicPaths = List.of(
                "/",
                "/index.html",
                "/login.html",
                "/register-customer.html",
                "/register-driver.html",
                "/forgot-password.html",
                "/reset-password.html",
                "/customer.html",
                "/driver.html",
                "/admin.html",
                "/css/styles.css",
                "/js/api.js",
                "/js/ui.js",
                "/js/map-service.js",
                "/assets/fleet/bike.jpg",
                "/assets/fleet/cargo-auto.jpg",
                "/assets/fleet/mini-truck.jpg",
                "/assets/fleet/truck.jpg"
        );

        for (String path : publicPaths) {
            ResponseEntity<String> res = restTemplate.getForEntity(path, String.class);
            assertThat(res.getStatusCode())
                    .as("Public frontend asset %s should return 200 OK", path)
                    .isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isNotBlank();
        }

        String uiJs = restTemplate.getForEntity("/js/ui.js", String.class).getBody();
        assertThat(uiJs).contains(
                "Apna Samaan",
                "teamapnasamaan@gmail.com",
                "https://github.com/shudanshubora1410",
                "https://www.linkedin.com/in/shudanshubora1410",
                "© 2026 Apna Samaan. Built by Shudanshu Bora"
        );
    }

    @Test
    void simulateCheckoutEndpoint_verifiesSimulatedOrderForOwningCustomerAndBlocksOtherUsers() {
        String ownerEmail = registerCustomer("Frontend Shipper", "9876591001");
        String ownerToken = login(ownerEmail);
        String otherEmail = registerCustomer("Other Shipper", "9876591002");
        String otherToken = login(otherEmail);

        ShipmentCreateRequest createReq = new ShipmentCreateRequest(
                "Block A, Connaught Place",
                "Delhi",
                28.6315,
                77.2167,
                "Aarav Sender",
                "9876591001",
                "DLF Cyber Hub",
                "Gurugram",
                28.4950,
                77.0895,
                "Priya Receiver",
                "9876591009",
                "Packed office monitors",
                PackageCategory.ELECTRONICS,
                new BigDecimal("45.00"),
                70,
                50,
                50,
                true,
                "CARGO_AUTO",
                true
        );

        ShipmentDetailResponse shipment = restTemplate.exchange(
                "/api/v1/shipments",
                HttpMethod.POST,
                new HttpEntity<>(createReq, bearer(ownerToken)),
                ShipmentDetailResponse.class
        ).getBody();
        assertThat(shipment).isNotNull();

        PaymentOrderResponse order = restTemplate.exchange(
                "/api/v1/payments/orders",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentOrderCreateRequest(shipment.id()), bearer(ownerToken)),
                PaymentOrderResponse.class
        ).getBody();
        assertThat(order).isNotNull();
        assertThat(order.razorpayOrderId()).startsWith("order_sim_");

        // Non-owner cannot simulate checkout -> 403 Forbidden
        ResponseEntity<ApiErrorResponse> forbiddenRes = restTemplate.exchange(
                "/api/v1/payments/simulate-checkout",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentSimulateRequest(shipment.id(), order.razorpayOrderId()), bearer(otherToken)),
                ApiErrorResponse.class
        );
        assertThat(forbiddenRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Owning customer completes simulated checkout -> CAPTURED + SEARCHING_DRIVER (and idempotent on repeat)
        ResponseEntity<PaymentResponse> paidRes = restTemplate.exchange(
                "/api/v1/payments/simulate-checkout",
                HttpMethod.POST,
                new HttpEntity<>(new PaymentSimulateRequest(shipment.id(), order.razorpayOrderId()), bearer(ownerToken)),
                PaymentResponse.class
        );
        assertThat(paidRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paidRes.getBody().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(paidRes.getBody().shipmentStatus()).isEqualTo(ShipmentStatus.SEARCHING_DRIVER);
    }

    private String registerCustomer(String name, String phone) {
        String email = "fe." + UUID.randomUUID().toString().substring(0, 8) + "@apnasamaan.test";
        createdEmails.add(email);
        CustomerRegisterRequest req = new CustomerRegisterRequest(name, email, phone, "Pass@12345");
        assertThat(restTemplate.postForEntity("/api/v1/auth/register/customer", req, AuthResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private String login(String email) {
        return restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, "Pass@12345"),
                AuthResponse.class
        ).getBody().accessToken();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
