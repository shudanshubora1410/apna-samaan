package com.sam.apnasamaan.common.mail;

import java.time.Instant;

public interface EmailService {

    void sendPasswordResetEmail(String recipientEmail, String recipientName, String resetLink, long expiryMinutes);

    void sendShipmentAssignedEmail(ShipmentAssignedMailData data);

    void sendShipmentDeliveredEmail(ShipmentDeliveredMailData data);

    default void sendShipmentAssignedEmail(
            String customerEmail,
            String customerName,
            String trackingCode,
            String driverName,
            String vehicleTypeName,
            String vehicleNumber,
            String pickupCity,
            String dropCity
    ) {
        sendShipmentAssignedEmail(new ShipmentAssignedMailData(
                customerEmail,
                customerName,
                trackingCode,
                driverName,
                null,
                vehicleTypeName,
                vehicleNumber,
                pickupCity,
                pickupCity,
                dropCity,
                dropCity
        ));
    }

    default void sendShipmentDeliveredEmail(
            String customerEmail,
            String customerName,
            String trackingCode,
            String pickupCity,
            String dropCity,
            String totalFareInr
    ) {
        sendShipmentDeliveredEmail(new ShipmentDeliveredMailData(
                customerEmail,
                customerName,
                trackingCode,
                null,
                null,
                null,
                pickupCity,
                pickupCity,
                dropCity,
                dropCity,
                totalFareInr,
                Instant.now()
        ));
    }
}
