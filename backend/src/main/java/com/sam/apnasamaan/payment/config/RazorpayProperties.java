package com.sam.apnasamaan.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.razorpay")
public record RazorpayProperties(
        String keyId,
        String keySecret,
        String webhookSecret,
        boolean simulationEnabled
) {
    private static final String SIM_DEFAULT_KEY_ID = "rzp_test_sim_local";
    private static final String SIM_DEFAULT_KEY_SECRET = "sim_local_razorpay_key_secret_apnasamaan";
    private static final String SIM_DEFAULT_WEBHOOK_SECRET = "sim_local_razorpay_webhook_secret_apnasamaan";

    public boolean hasLiveCredentials() {
        return isRealValue(keyId) && isRealValue(keySecret);
    }

    public boolean hasWebhookSecret() {
        return isRealValue(webhookSecret);
    }

    public String effectiveKeyId() {
        if (isRealValue(keyId)) {
            return keyId.trim();
        }
        return simulationEnabled ? SIM_DEFAULT_KEY_ID : "";
    }

    public String effectiveKeySecret() {
        if (isRealValue(keySecret)) {
            return keySecret.trim();
        }
        return simulationEnabled ? SIM_DEFAULT_KEY_SECRET : "";
    }

    public String effectiveWebhookSecret() {
        if (isRealValue(webhookSecret)) {
            return webhookSecret.trim();
        }
        return simulationEnabled ? SIM_DEFAULT_WEBHOOK_SECRET : "";
    }

    private static boolean isRealValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.contains("CHANGE_ME");
    }
}
