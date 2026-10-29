package com.sam.apnasamaan.common.mail;

import com.sam.apnasamaan.common.config.AppProperties;
import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Production transactional email service backed by Gmail SMTP:
 * - Dispatches ONLY after the surrounding database transaction commits (`afterCommit`).
 * - Runs asynchronously on a background worker thread so SMTP TLS latency never blocks API responses.
 * - Sends responsive multipart (`text/plain` + branded `text/html`) emails with safe HTML escaping.
 */
@Slf4j
@Service
public class SmtpEmailService implements EmailService {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter IST_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a 'IST'", Locale.ENGLISH).withZone(IST_ZONE);

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;
    private final String fromAddress;
    private final String smtpPassword;
    private final String frontendBaseUrl;
    private final boolean asyncEnabled;
    private final ExecutorService mailExecutor;

    @Autowired
    public SmtpEmailService(
            JavaMailSender mailSender,
            AppProperties appProperties,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${spring.mail.password:}") String smtpPassword,
            @Value("${app.password-reset.frontend-base-url:http://localhost:8080}") String frontendBaseUrl,
            @Value("${app.mail.async-enabled:true}") boolean asyncEnabled
    ) {
        this.mailSender = mailSender;
        this.appProperties = appProperties;
        this.fromAddress = (mailUsername != null && !mailUsername.isBlank())
                ? mailUsername.trim()
                : appProperties.supportEmail();
        this.smtpPassword = normalizeMailPassword(mailSender, mailUsername, smtpPassword);
        this.frontendBaseUrl = normalizeBaseUrl(frontendBaseUrl);
        this.asyncEnabled = asyncEnabled;
        this.mailExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread worker = new Thread(runnable, "apna-mail-worker");
            worker.setDaemon(true);
            return worker;
        });
    }

    public SmtpEmailService(
            JavaMailSender mailSender,
            AppProperties appProperties,
            String smtpPassword
    ) {
        this(mailSender, appProperties, appProperties.supportEmail(), smtpPassword, "http://localhost:8080", false);
    }

    @PreDestroy
    void shutDownExecutor() {
        mailExecutor.shutdown();
    }

    @Override
    public void sendPasswordResetEmail(
            String recipientEmail,
            String recipientName,
            String resetLink,
            long expiryMinutes
    ) {
        // Skip network call cleanly if developer has not set MAIL_PASSWORD yet; never log resetLink/token
        if (!isSmtpConfigured()) {
            log.warn("MAIL_PASSWORD is not configured in .env; skipping password-reset email for {}", recipientEmail);
            return;
        }

        String safeName = fallback(recipientName, "Shipper");
        String plainText = """
                Hello %s,

                We received a request to reset the password for your Apna Samaan account.
                Use the secure single-use link below within %d minutes:

                %s

                If you did not request this password reset, you can safely ignore this email — your account remains secure.

                Regards,
                Team Apna Samaan
                %s
                """.formatted(safeName, expiryMinutes, resetLink, appProperties.supportEmail());

        String htmlBody = buildBrandedEmailHtml(
                "PASSWORD RECOVERY",
                "#ea580c",
                "Reset your Apna Samaan password",
                "Hello <strong>" + esc(safeName) + "</strong>, we received a request to reset the password for your Apna Samaan account.",
                """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;padding:14px 16px;margin:16px 0;">
                  <tr>
                    <td style="font-size:13px;color:#334155;line-height:1.5;">
                      <strong>Security Notice:</strong> This password reset link is valid for <strong>%d minutes</strong> and can only be used <strong>once</strong>. All active sessions will be signed out automatically once your new password is saved.
                    </td>
                  </tr>
                </table>
                """.formatted(expiryMinutes),
                "Reset My Password →",
                resetLink,
                "If the button above does not open, copy and paste this URL into your browser:<br><span style=\"word-break:break-all;color:#0f172a;font-family:monospace;font-size:12px;\">"
                        + esc(resetLink)
                        + "</span><br><br>If you did not request this reset, you can safely ignore this message."
        );

        scheduleAfterCommit(() -> dispatch(
                recipientEmail,
                "Apna Samaan — Reset Your Password",
                plainText,
                htmlBody,
                "password-reset"
        ));
    }

    @Override
    public void sendShipmentAssignedEmail(ShipmentAssignedMailData data) {
        if (!isSmtpConfigured()) {
            log.warn("MAIL_PASSWORD is not configured in .env; skipping ASSIGNED email for trackingCode={}",
                    data.trackingCode());
            return;
        }

        String safeCustomer = fallback(data.customerName(), "Shipper");
        String driverMobileLine = (data.driverPhone() != null && !data.driverPhone().isBlank())
                ? data.driverPhone().trim()
                : "Available in live dashboard";
        String pickupSummary = formatStop(data.pickupAddress(), data.pickupCity());
        String dropSummary = formatStop(data.dropAddress(), data.dropCity());
        String trackingUrl = frontendBaseUrl + "/customer.html";

        String plainText = """
                Hello %s,

                Great news! A verified driver partner has accepted your Apna Samaan shipment and is heading toward pickup.

                Tracking Code : %s
                Route         : %s -> %s
                Pickup Stop   : %s
                Drop-off Stop : %s
                Driver        : %s
                Driver Mobile : %s
                Vehicle       : %s (%s)

                Track live GPS progress on your Shipper Dashboard:
                %s

                Regards,
                Team Apna Samaan
                %s
                """.formatted(
                safeCustomer,
                data.trackingCode(),
                data.pickupCity(),
                data.dropCity(),
                pickupSummary,
                dropSummary,
                data.driverName(),
                driverMobileLine,
                data.vehicleTypeName(),
                data.vehicleNumber(),
                trackingUrl,
                appProperties.supportEmail()
        );

        String detailsTable = """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;padding:14px 16px;margin:16px 0;font-size:13px;color:#1e293b;">
                  <tr><td style="padding:5px 0;color:#64748b;width:38%%;">Waybill Code</td><td style="padding:5px 0;font-weight:700;font-family:monospace;">%s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Corridor</td><td style="padding:5px 0;font-weight:600;">%s &rarr; %s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Pickup</td><td style="padding:5px 0;">%s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Drop-off</td><td style="padding:5px 0;">%s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Assigned Driver</td><td style="padding:5px 0;font-weight:600;">%s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Driver Contact</td><td style="padding:5px 0;font-family:monospace;">%s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Vehicle</td><td style="padding:5px 0;font-weight:600;">%s &middot; <span style="font-family:monospace;">%s</span></td></tr>
                </table>
                """.formatted(
                esc(data.trackingCode()),
                esc(data.pickupCity()),
                esc(data.dropCity()),
                esc(pickupSummary),
                esc(dropSummary),
                esc(data.driverName()),
                esc(driverMobileLine),
                esc(data.vehicleTypeName()),
                esc(data.vehicleNumber())
        );

        String htmlBody = buildBrandedEmailHtml(
                "DRIVER ASSIGNED",
                "#059669",
                "Your driver is on the way to pickup",
                "Hello <strong>" + esc(safeCustomer) + "</strong>, a verified Apna Samaan driver partner has accepted your booking and is en route to the pickup point.",
                detailsTable,
                "Track Live Shipment →",
                trackingUrl,
                "Keep your cargo ready at the pickup point for smooth loading."
        );

        scheduleAfterCommit(() -> dispatch(
                data.customerEmail(),
                "Apna Samaan — Driver Assigned (" + data.trackingCode() + ")",
                plainText,
                htmlBody,
                "shipment-assigned"
        ));
    }

    @Override
    public void sendShipmentDeliveredEmail(ShipmentDeliveredMailData data) {
        if (!isSmtpConfigured()) {
            log.warn("MAIL_PASSWORD is not configured in .env; skipping DELIVERED email for trackingCode={}",
                    data.trackingCode());
            return;
        }

        String safeCustomer = fallback(data.customerName(), "Shipper");
        String pickupSummary = formatStop(data.pickupAddress(), data.pickupCity());
        String dropSummary = formatStop(data.dropAddress(), data.dropCity());
        Instant completedAt = data.deliveredAt() != null ? data.deliveredAt() : Instant.now();
        String deliveredTimeIst = IST_FORMATTER.format(completedAt);
        String driverLine = buildDriverVehicleLine(data.driverName(), data.vehicleTypeName(), data.vehicleNumber());
        String invoiceUrl = frontendBaseUrl + "/customer.html";

        String plainText = """
                Hello %s,

                Your Apna Samaan shipment has been delivered successfully!

                Tracking Code : %s
                Delivered At  : %s
                Route         : %s -> %s
                Drop-off Stop : %s
                Completed By  : %s
                Total Fare    : INR %s

                You can download your freight waybill and GST tax invoice anytime from your Shipper Dashboard:
                %s

                Thank you for shipping with Apna Samaan. For any support, write to %s.

                Regards,
                Team Apna Samaan
                %s
                """.formatted(
                safeCustomer,
                data.trackingCode(),
                deliveredTimeIst,
                data.pickupCity(),
                data.dropCity(),
                dropSummary,
                driverLine,
                data.totalFareInr(),
                invoiceUrl,
                appProperties.supportEmail(),
                appProperties.supportEmail()
        );

        String detailsTable = """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;padding:14px 16px;margin:16px 0;font-size:13px;color:#1e293b;">
                  <tr><td style="padding:5px 0;color:#64748b;width:38%%;">Waybill Code</td><td style="padding:5px 0;font-weight:700;font-family:monospace;">%s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Status</td><td style="padding:5px 0;font-weight:700;color:#059669;">DELIVERED</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Delivered At</td><td style="padding:5px 0;">%s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Route</td><td style="padding:5px 0;font-weight:600;">%s &rarr; %s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Pickup</td><td style="padding:5px 0;">%s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Delivered To</td><td style="padding:5px 0;">%s</td></tr>
                  <tr><td style="padding:5px 0;color:#64748b;">Driver &amp; Fleet</td><td style="padding:5px 0;">%s</td></tr>
                  <tr><td style="padding:6px 0 2px;color:#0f172a;font-weight:700;border-top:1px solid #e2e8f0;">Total Freight Fare</td><td style="padding:6px 0 2px;font-weight:700;font-family:monospace;color:#0f172a;border-top:1px solid #e2e8f0;">&#8377;%s (INR %s)</td></tr>
                </table>
                """.formatted(
                esc(data.trackingCode()),
                esc(deliveredTimeIst),
                esc(data.pickupCity()),
                esc(data.dropCity()),
                esc(pickupSummary),
                esc(dropSummary),
                esc(driverLine),
                esc(data.totalFareInr()),
                esc(data.totalFareInr())
        );

        String htmlBody = buildBrandedEmailHtml(
                "DELIVERY CONFIRMED",
                "#059669",
                "Your shipment has been delivered",
                "Hello <strong>" + esc(safeCustomer) + "</strong>, your consignment has reached its destination and has been marked delivered by your driver partner.",
                detailsTable,
                "View Waybill & Download Invoice →",
                invoiceUrl,
                "Thank you for choosing Apna Samaan for your logistics."
        );

        scheduleAfterCommit(() -> dispatch(
                data.customerEmail(),
                "Apna Samaan — Shipment Delivered (" + data.trackingCode() + ")",
                plainText,
                htmlBody,
                "shipment-delivered"
        ));
    }

    private boolean isSmtpConfigured() {
        return smtpPassword != null && !smtpPassword.isBlank() && !smtpPassword.startsWith("CHANGE_ME");
    }

    private void scheduleAfterCommit(Runnable mailTask) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitOrRunInline(mailTask);
                }
            });
        } else {
            submitOrRunInline(mailTask);
        }
    }

    private void submitOrRunInline(Runnable mailTask) {
        if (asyncEnabled) {
            mailExecutor.execute(mailTask);
        } else {
            mailTask.run();
        }
    }

    private void dispatch(
            String recipientEmail,
            String subject,
            String plainTextBody,
            String htmlBody,
            String label
    ) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            if (mimeMessage != null) {
                MimeMessageHelper helper = new MimeMessageHelper(
                        mimeMessage,
                        true,
                        StandardCharsets.UTF_8.name()
                );
                helper.setFrom(fromAddress, "Apna Samaan");
                helper.setReplyTo(appProperties.supportEmail());
                helper.setTo(recipientEmail);
                helper.setSubject(subject);
                helper.setText(plainTextBody, htmlBody);
                mailSender.send(mimeMessage);
            } else {
                // Fallback when JavaMailSender is an unstubbed Mockito mock in unit/integration tests
                SimpleMailMessage fallbackMessage = new SimpleMailMessage();
                fallbackMessage.setFrom(fromAddress);
                fallbackMessage.setReplyTo(appProperties.supportEmail());
                fallbackMessage.setTo(recipientEmail);
                fallbackMessage.setSubject(subject);
                fallbackMessage.setText(plainTextBody);
                mailSender.send(fallbackMessage);
            }
            log.info("Dispatched {} email to {}", label, recipientEmail);
        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            // Never fail user-facing workflows if external SMTP is temporarily unreachable
            log.error("Failed to send {} email to {}: {}", label, recipientEmail, ex.getMessage());
        }
    }

    private String buildBrandedEmailHtml(
            String badgeText,
            String badgeColor,
            String headline,
            String introHtml,
            String contentHtml,
            String ctaLabel,
            String ctaUrl,
            String footerNoteHtml
    ) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <body style="margin:0;padding:24px 12px;background:#f4f5f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#0f172a;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:580px;margin:0 auto;background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;">
                    <tr>
                      <td style="background:#0f172a;padding:18px 24px;">
                        <span style="font-size:18px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">Apna Samaan</span>
                        <span style="display:inline-block;margin-left:10px;padding:3px 8px;border-radius:999px;background:%s;color:#ffffff;font-size:10px;font-weight:700;letter-spacing:0.06em;">%s</span>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:24px;">
                        <h1 style="margin:0 0 10px;font-size:20px;line-height:1.3;color:#0f172a;">%s</h1>
                        <p style="margin:0 0 14px;font-size:14px;line-height:1.6;color:#334155;">%s</p>
                        %s
                        <div style="margin:22px 0;">
                          <a href="%s" style="display:inline-block;background:#ea580c;color:#ffffff;text-decoration:none;font-weight:700;font-size:14px;padding:11px 20px;border-radius:8px;">%s</a>
                        </div>
                        <p style="margin:0;font-size:12px;line-height:1.6;color:#64748b;">%s</p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:14px 24px;background:#f8fafc;border-top:1px solid #e2e8f0;font-size:12px;color:#64748b;">
                        Need assistance? Contact <a href="mailto:%s" style="color:#0f172a;font-weight:600;text-decoration:none;">%s</a><br>
                        &copy; 2026 Apna Samaan &middot; Intra-City &amp; Inter-City Goods Logistics
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                badgeColor,
                esc(badgeText),
                esc(headline),
                introHtml,
                contentHtml,
                esc(ctaUrl),
                esc(ctaLabel),
                footerNoteHtml,
                esc(appProperties.supportEmail()),
                esc(appProperties.supportEmail())
        );
    }

    private static String normalizeMailPassword(
            JavaMailSender sender,
            String rawUsername,
            String rawPassword
    ) {
        if (rawPassword == null) {
            return "";
        }
        String trimmed = rawPassword.trim();
        if (sender instanceof JavaMailSenderImpl impl) {
            if (rawUsername != null && !rawUsername.isBlank()) {
                impl.setUsername(rawUsername.trim());
            }
            String host = impl.getHost() != null ? impl.getHost().toLowerCase(Locale.ROOT) : "";
            // Google displays 16-char App Passwords in 4-char groups ("xxxx xxxx xxxx xxxx"); strip spaces safely
            if (host.contains("gmail.com") && !trimmed.isBlank() && !trimmed.startsWith("CHANGE_ME")) {
                String compact = trimmed.replaceAll("\\s+", "");
                impl.setPassword(compact);
                return compact;
            }
        }
        return trimmed;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String candidate = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:8080" : baseUrl.trim();
        return candidate.replaceAll("/+$", "");
    }

    private static String formatStop(String address, String city) {
        if (address == null || address.isBlank()) {
            return fallback(city, "India");
        }
        String trimmedAddr = address.trim();
        if (city == null || city.isBlank() || trimmedAddr.toLowerCase(Locale.ROOT).contains(city.trim().toLowerCase(Locale.ROOT))) {
            return trimmedAddr;
        }
        return trimmedAddr + ", " + city.trim();
    }

    private static String buildDriverVehicleLine(String driverName, String vehicleTypeName, String vehicleNumber) {
        String name = fallback(driverName, "Verified Driver Partner");
        if (vehicleTypeName != null && !vehicleTypeName.isBlank() && vehicleNumber != null && !vehicleNumber.isBlank()) {
            return name + " (" + vehicleTypeName.trim() + " · " + vehicleNumber.trim() + ")";
        }
        return name;
    }

    private static String fallback(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static String esc(String raw) {
        return HtmlUtils.htmlEscape(raw != null ? raw : "");
    }
}
