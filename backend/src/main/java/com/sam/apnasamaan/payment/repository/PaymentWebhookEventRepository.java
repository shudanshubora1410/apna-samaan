package com.sam.apnasamaan.payment.repository;

import com.sam.apnasamaan.payment.entity.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {

    boolean existsByEventKey(String eventKey);
}
