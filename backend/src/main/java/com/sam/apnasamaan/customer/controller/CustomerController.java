package com.sam.apnasamaan.customer.controller;

import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.config.OpenApiConfig;
import com.sam.apnasamaan.customer.dto.CustomerProfileResponse;
import com.sam.apnasamaan.customer.dto.CustomerProfileUpdateRequest;
import com.sam.apnasamaan.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
@Tag(name = "Customer", description = "Customer profile operations (ownership enforced)")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "View own customer profile")
    public ResponseEntity<CustomerProfileResponse> getMyProfile(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(customerService.getCustomerProfile(principal.getId(), principal));
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Update own customer name and phone number")
    public ResponseEntity<CustomerProfileResponse> updateMyProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CustomerProfileUpdateRequest request
    ) {
        return ResponseEntity.ok(customerService.updateCustomerProfile(principal.getId(), principal, request));
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @Operation(
            summary = "Get customer profile by ID (owner CUSTOMER or ADMIN only)",
            description = "Demonstrates role + service-layer ownership checks: a customer cannot read another customer's ID."
    )
    public ResponseEntity<CustomerProfileResponse> getCustomerById(
            @PathVariable Long customerId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(customerService.getCustomerProfile(customerId, principal));
    }
}
