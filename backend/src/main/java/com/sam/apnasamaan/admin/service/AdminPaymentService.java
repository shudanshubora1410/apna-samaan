package com.sam.apnasamaan.admin.service;

import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.payment.dto.PaymentResponse;
import com.sam.apnasamaan.payment.entity.Payment;
import com.sam.apnasamaan.payment.entity.PaymentStatus;
import com.sam.apnasamaan.payment.repository.PaymentRepository;
import com.sam.apnasamaan.payment.service.PaymentMapper;
import com.sam.apnasamaan.payment.service.PaymentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class AdminPaymentService {

    private static final int MAX_PAGE_SIZE = 50;

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final PaymentService paymentService;

    public AdminPaymentService(
            PaymentRepository paymentRepository,
            PaymentMapper paymentMapper,
            PaymentService paymentService
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
        this.paymentService = paymentService;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PaymentResponse> searchPayments(
            PaymentStatus status,
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

        Page<Payment> result = paymentRepository.searchPaymentsForAdmin(status, searchPattern, pageable);
        List<PaymentResponse> content = result.getContent().stream()
                .map(paymentMapper::toResponse)
                .toList();

        return new PagedResponse<>(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isLast()
        );
    }

    @Transactional
    public PaymentResponse issueRefund(Long paymentId, String reason) {
        return paymentService.refundPaymentByAdmin(paymentId, reason);
    }
}
