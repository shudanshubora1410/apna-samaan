package com.sam.apnasamaan.admin.controller;

import com.sam.apnasamaan.admin.service.AdminPaymentService;
import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.payment.dto.PaymentRefundRequest;
import com.sam.apnasamaan.payment.dto.PaymentResponse;
import com.sam.apnasamaan.payment.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin - Payments", description = "Admin payment ledger search and operational refund controls")
public class AdminPaymentController {

    private final AdminPaymentService adminPaymentService;

    public AdminPaymentController(AdminPaymentService adminPaymentService) {
        this.adminPaymentService = adminPaymentService;
    }

    @GetMapping
    @Operation(summary = "Search and filter payments by status, tracking code, Razorpay IDs, or customer")
    public ResponseEntity<PagedResponse<PaymentResponse>> searchPayments(
            @RequestParam(name = "status", required = false) PaymentStatus status,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(adminPaymentService.searchPayments(status, search, page, size));
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Issue a full Razorpay refund for a CAPTURED payment")
    public ResponseEntity<PaymentResponse> refundPayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody PaymentRefundRequest request
    ) {
        return ResponseEntity.ok(adminPaymentService.issueRefund(paymentId, request.reason()));
    }
}
