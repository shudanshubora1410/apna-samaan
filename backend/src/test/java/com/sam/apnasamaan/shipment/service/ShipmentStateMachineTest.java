package com.sam.apnasamaan.shipment.service;

import com.sam.apnasamaan.common.exception.BadRequestException;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentStateMachineTest {

    private final ShipmentStateMachine stateMachine = new ShipmentStateMachine();

    @Test
    void happyPathLifecycleTransitions_arePermitted() {
        stateMachine.assertTransitionAllowed(ShipmentStatus.PENDING_PAYMENT, ShipmentStatus.SEARCHING_DRIVER);
        stateMachine.assertTransitionAllowed(ShipmentStatus.SEARCHING_DRIVER, ShipmentStatus.ASSIGNED);
        stateMachine.assertTransitionAllowed(ShipmentStatus.ASSIGNED, ShipmentStatus.PICKED_UP);
        stateMachine.assertTransitionAllowed(ShipmentStatus.PICKED_UP, ShipmentStatus.IN_TRANSIT);
        stateMachine.assertTransitionAllowed(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED);
        stateMachine.assertTransitionAllowed(ShipmentStatus.SEARCHING_DRIVER, ShipmentStatus.FAILED);
    }

    @Test
    void customerCancellation_isAllowedOnlyBeforePickedUp() {
        assertThat(stateMachine.canCustomerCancel(ShipmentStatus.PENDING_PAYMENT)).isTrue();
        assertThat(stateMachine.canCustomerCancel(ShipmentStatus.SEARCHING_DRIVER)).isTrue();
        assertThat(stateMachine.canCustomerCancel(ShipmentStatus.ASSIGNED)).isTrue();

        assertThat(stateMachine.canCustomerCancel(ShipmentStatus.PICKED_UP)).isFalse();
        assertThat(stateMachine.canCustomerCancel(ShipmentStatus.IN_TRANSIT)).isFalse();
        assertThat(stateMachine.canCustomerCancel(ShipmentStatus.DELIVERED)).isFalse();
        assertThat(stateMachine.canCustomerCancel(ShipmentStatus.CANCELLED)).isFalse();
        assertThat(stateMachine.canCustomerCancel(ShipmentStatus.FAILED)).isFalse();
    }

    @Test
    void illegalTransitions_throwBadRequestException() {
        assertThatThrownBy(() ->
                stateMachine.assertTransitionAllowed(ShipmentStatus.PENDING_PAYMENT, ShipmentStatus.DELIVERED))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Illegal shipment status transition");

        assertThatThrownBy(() ->
                stateMachine.assertTransitionAllowed(ShipmentStatus.PICKED_UP, ShipmentStatus.CANCELLED))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() ->
                stateMachine.assertTransitionAllowed(ShipmentStatus.DELIVERED, ShipmentStatus.IN_TRANSIT))
                .isInstanceOf(BadRequestException.class);
    }
}
