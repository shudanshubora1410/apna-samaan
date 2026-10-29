package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentActorRole;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.entity.ShipmentStatusHistory;
import com.sam.apnasamaan.shipment.repository.ShipmentStatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dedicated status-transition collaborator shared by ShipmentService, PaymentService,
 * and Phase 5 matching/delivery services without creating circular Spring bean dependencies.
 */
@Service
public class ShipmentTransitionService {

    private final ShipmentStateMachine stateMachine;
    private final ShipmentStatusHistoryRepository historyRepository;

    public ShipmentTransitionService(
            ShipmentStateMachine stateMachine,
            ShipmentStatusHistoryRepository historyRepository
    ) {
        this.stateMachine = stateMachine;
        this.historyRepository = historyRepository;
    }

    @Transactional
    public void recordInitialCreation(Shipment shipment, User customer, String note) {
        saveHistory(shipment, null, ShipmentStatus.PENDING_PAYMENT, customer, ShipmentActorRole.CUSTOMER, note);
    }

    @Transactional
    public void transition(
            Shipment shipment,
            ShipmentStatus targetStatus,
            User actor,
            ShipmentActorRole actorRole,
            String note
    ) {
        ShipmentStatus previous = shipment.getStatus();
        stateMachine.assertTransitionAllowed(previous, targetStatus);
        shipment.setStatus(targetStatus);
        saveHistory(shipment, previous, targetStatus, actor, actorRole, note);
    }

    private void saveHistory(
            Shipment shipment,
            ShipmentStatus fromStatus,
            ShipmentStatus toStatus,
            User actor,
            ShipmentActorRole actorRole,
            String note
    ) {
        ShipmentStatusHistory history = ShipmentStatusHistory.builder()
                .shipment(shipment)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .changedByUser(actor)
                .changedByRole(actorRole)
                .note(note)
                .build();
        historyRepository.save(history);
    }
}
