package com.sam.apnasamaan.common.mail;

import com.sam.apnasamaan.common.config.AppProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmtpEmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private final AppProperties appProperties = new AppProperties(
            "0.8.0",
            "teamapnasamaan@gmail.com",
            new AppProperties.Cors(List.of("http://localhost:8080"))
    );

    @Test
    void sendPasswordResetEmail_skipsWhenPasswordIsPlaceholderOrBlank() {
        SmtpEmailService unconfigured = new SmtpEmailService(mailSender, appProperties, "CHANGE_ME_GMAIL_APP_PASSWORD");
        unconfigured.sendPasswordResetEmail("user@example.com", "Aarav", "http://localhost:8080/reset?token=abc", 15);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendAllThreeEmails_rendersBrandedMultipartHtmlAndEscapesUntrustedStrings() throws Exception {
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));

        SmtpEmailService service = new SmtpEmailService(
                mailSender,
                appProperties,
                "teamapnasamaan@gmail.com",
                "valid-app-password-token",
                "http://localhost:8080/",
                false
        );

        String resetLink = "http://localhost:8080/reset-password.html?token=xyz123";
        service.sendPasswordResetEmail("aarav@example.com", "Aarav <script>", resetLink, 15);

        service.sendShipmentAssignedEmail(new ShipmentAssignedMailData(
                "priya@example.com",
                "Priya Verma",
                "APS-20261028-AB12CD",
                "Ramesh Kumar",
                "9876543210",
                "Mini Truck",
                "DL01AB4321",
                "Okhla Phase 2, New Delhi",
                "New Delhi",
                "Sector 62, Noida",
                "Noida"
        ));

        service.sendShipmentDeliveredEmail(new ShipmentDeliveredMailData(
                "priya@example.com",
                "Priya Verma",
                "APS-20261028-AB12CD",
                "Ramesh Kumar",
                "Mini Truck",
                "DL01AB4321",
                "Okhla Phase 2, New Delhi",
                "New Delhi",
                "Sector 62, Noida",
                "Noida",
                "1020.46",
                Instant.parse("2026-10-28T13:15:00Z")
        ));

        ArgumentCaptor<MimeMessage> mimeCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(3)).send(mimeCaptor.capture());
        List<MimeMessage> sent = mimeCaptor.getAllValues();

        // 1. Password Reset Multipart Check + XSS Escaping in HTML Part
        assertThat(sent.get(0).getSubject()).isEqualTo("Apna Samaan — Reset Your Password");
        String resetContent = extractAllText(sent.get(0).getContent());
        assertThat(resetContent)
                .contains("Reset My Password", resetLink, "15 minutes", "<strong>Aarav &lt;script&gt;</strong>")
                .doesNotContain("<strong>Aarav <script></strong>");

        // 2. Driver Acceptance Notification Check
        assertThat(sent.get(1).getSubject()).isEqualTo("Apna Samaan — Driver Assigned (APS-20261028-AB12CD)");
        String assignedContent = extractAllText(sent.get(1).getContent());
        assertThat(assignedContent).contains(
                "DRIVER ASSIGNED",
                "Ramesh Kumar",
                "9876543210",
                "Mini Truck",
                "DL01AB4321",
                "Okhla Phase 2, New Delhi",
                "Sector 62, Noida",
                "http://localhost:8080/customer.html"
        );

        // 3. Order Delivery Notification Check
        assertThat(sent.get(2).getSubject()).isEqualTo("Apna Samaan — Shipment Delivered (APS-20261028-AB12CD)");
        String deliveredContent = extractAllText(sent.get(2).getContent());
        assertThat(deliveredContent).contains(
                "DELIVERY CONFIRMED",
                "1020.46",
                "IST",
                "Ramesh Kumar",
                "DL01AB4321",
                "http://localhost:8080/customer.html"
        );
    }

    @Test
    void gmailAppPasswordWithSpaces_isNormalizedAndTransactionRollbackSkipsSend() {
        JavaMailSenderImpl gmailImpl = new JavaMailSenderImpl();
        gmailImpl.setHost("smtp.gmail.com");

        SmtpEmailService service = new SmtpEmailService(
                gmailImpl,
                appProperties,
                "  teamapnasamaan@gmail.com  ",
                "abcd efgh ijkl mnop",
                "http://localhost:8080",
                false
        );
        assertThat(gmailImpl.getUsername()).isEqualTo("teamapnasamaan@gmail.com");
        assertThat(gmailImpl.getPassword()).isEqualTo("abcdefghijklmnop");

        // Verify afterCommit synchronization: rollback must never trigger mailSender.send
        SmtpEmailService mockBacked = new SmtpEmailService(mailSender, appProperties, "valid-app-password-token");
        TransactionSynchronizationManager.initSynchronization();
        try {
            mockBacked.sendShipmentDeliveredEmail(
                    "priya@example.com",
                    "Priya Verma",
                    "APS-20261028-ROLLBK",
                    "Delhi",
                    "Gurgaon",
                    "540.00"
            );
            // Not sent before commit
            verify(mailSender, never()).send(any(SimpleMailMessage.class));

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            // Simulate commit -> now it sends
            syncs.get(0).afterCommit();
            verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendPasswordResetEmail_swallowsMailExceptionCleanly() {
        SmtpEmailService service = new SmtpEmailService(mailSender, appProperties, "valid-app-password-token");
        doThrow(new MailSendException("SMTP timeout")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> service.sendPasswordResetEmail(
                "aarav@example.com",
                "Aarav",
                "http://localhost:8080/reset?token=xyz",
                15
        )).doesNotThrowAnyException();
    }

    private static String extractAllText(Object content) throws Exception {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof Multipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                sb.append(extractAllText(part.getContent())).append('\n');
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }
}
