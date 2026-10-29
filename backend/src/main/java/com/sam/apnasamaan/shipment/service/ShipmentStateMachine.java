package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralizes all allowed shipment state transitions so controllers and services
 * across booking, payment, driver matching, and delivery cannot bypass lifecycle rules.
 */
@Component
public class ShipmentStateMachine {

    private static final Set<ShipmentStatus> CUSTOMER_CANCELLABLE_STATES = EnumSet.of(
            ShipmentStatus.PENDING_PAYMENT,
            ShipmentStatus.SEARCHING_DRIVER,
            ShipmentStatus.ASSIGNED
    );

    private final Map<ShipmentStatus, Set<ShipmentStatus>> allowedTransitions;

    public ShipmentStateMachine() {
        Map<ShipmentStatus, Set<ShipmentStatus>> map = new EnumMap<>(ShipmentStatus.class);
        map.put(ShipmentStatus.PENDING_PAYMENT, EnumSet.of(
                ShipmentStatus.SEARCHING_DRIVER,
                ShipmentStatus.CANCELLED
        ));
        map.put(ShipmentStatus.SEARCHING_DRIVER, EnumSet.of(
                ShipmentStatus.ASSIGNED,
                ShipmentStatus.CANCELLED,
                ShipmentStatus.FAILED
        ));
        map.put(ShipmentStatus.ASSIGNED, EnumSet.of(
                ShipmentStatus.PICKED_UP,
                ShipmentStatus.CANCELLED
        ));
        map.put(ShipmentStatus.PICKED_UP, EnumSet.of(ShipmentStatus.IN_TRANSIT));
        map.put(ShipmentStatus.IN_TRANSIT, EnumSet.of(ShipmentStatus.DELIVERED));
        map.put(ShipmentStatus.DELIVERED, Collections.emptySet());
        map.put(ShipmentStatus.CANCELLED, Collections.emptySet());
        map.put(ShipmentStatus.FAILED, Collections.emptySet());
        this.allowedTransitions = Collections.unmodifiableMap(map);
    }

    public boolean canTransition(ShipmentStatus current, ShipmentStatus target) {
        if (current == null || target == null) {
            return false;
        }
        return allowedTransitions.getOrDefault(current, Collections.emptySet()).contains(target);
    }

    public void assertTransitionAllowed(ShipmentStatus current, ShipmentStatus target) {
        if (!canTransition(current, target)) {
            throw new BadRequestException(
                    "Illegal shipment status transition from " + current + " to " + target
            );
        }
    }

    public boolean canCustomerCancel(ShipmentStatus current) {
        return current != null && CUSTOMER_CANCELLABLE_STATES.contains(current);
    }
}
