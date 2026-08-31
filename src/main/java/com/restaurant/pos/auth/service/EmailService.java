package com.restaurant.pos.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.common.exception.EmailDeliveryException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Async;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender emailSender;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final String PROVIDER_GMAIL_API = "gmail-api";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GMAIL_SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.port:587}")
    private int mailPort;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${email.provider:smtp}")
    private String emailProvider;

    @Value("${gmail.api.client-id:}")
    private String gmailClientId;

    @Value("${gmail.api.client-secret:}")
    private String gmailClientSecret;

    @Value("${gmail.api.refresh-token:}")
    private String gmailRefreshToken;

    @Value("${gmail.api.sender-email:}")
    private String gmailSenderEmail;

    @Value("${app.otp.log-code:false}")
    private boolean logOtpCode;

    @PostConstruct
    void logMailConfiguration() {
        log.info("Mail configuration loaded. provider={}, smtpHost={}, smtpPort={}, smtpUsernameConfigured={}, smtpPasswordConfigured={}, gmailApiConfigured={}",
                safe(emailProvider),
                safe(mailHost),
                mailPort,
                !isBlank(fromEmail),
                !isBlank(mailPassword),
                isGmailApiConfigured());
    }

    public void sendOtpEmail(String toEmail, String otp) {
        String body = """
                Welcome to CafeQR!

                Your one-time password (OTP) is: %s

                This OTP is valid for 5 minutes.
                """.formatted(otp);

        logOtpForDebugging(toEmail, otp);
        try {
            sendPlainTextEmail(toEmail, "Your CafeQR verification code", body, "OTP email");
        } catch (Exception e) {
            log.warn("SMTP email delivery failed for {}: {}. OTP code logged for verification.", toEmail, e.getMessage());
            if (!logOtpCode) {
                throw e;
            }
        }
    }

    public void sendWelcomeCredentialsEmail(String toEmail, String name, String planName, String password) {
        String body = String.format("""
                Dear %s,

                Thank you for your purchase of %s!
                Your CafeQR POS account has been successfully created and activated with a 1-Year Core License.

                YOUR LOGIN CREDENTIALS:
                ---------------------------------------
                Portal URL: https://pos.cafeqr.in/login
                Email ID:   %s
                Password:   %s
                ---------------------------------------

                Next steps:
                1. Login to your POS portal using the credentials above.
                2. We recommend changing your password from Profile Settings.
                3. Our onboarding team will connect with you to guide you through initial setup and printer delivery tracking.

                Need help? Reach out to us at pnriyas50@gmail.com or via WhatsApp support.

                Welcome aboard,
                Team CafeQR
                """,
                name != null && !name.isBlank() ? name : "Partner",
                planName != null ? planName : "CafeQR POS",
                toEmail,
                password
        );

        sendPlainTextEmail(toEmail, "🎉 Welcome to CafeQR POS! Your Account Credentials", body, "Welcome Credentials Email");
    }

    private void sendPlainTextEmail(String toEmail, String subject, String body, String logLabel) {
        if (shouldUseGmailApi()) {
            sendPlainTextEmailViaGmailApi(toEmail, subject, body, logLabel);
        } else {
            sendPlainTextEmailViaSmtp(toEmail, subject, body, logLabel);
        }
    }

    @Async
    public void sendTableQREmail(String toEmail, String tableNumber, String qrLink) {
        // Log the link prominently in the terminal as per "login/signup" strategy
        System.out.println("\n[MAIL SIMULATOR] ------------------------------------------------");
        System.out.println("[MAIL SIMULATOR] TABLE: " + tableNumber);
        System.out.println("[MAIL SIMULATOR] TO: " + toEmail);
        System.out.println("[MAIL SIMULATOR] LINK: " + qrLink);
        System.out.println("[MAIL SIMULATOR] ------------------------------------------------\n");
        
        log.info("Requested QR email for Table {} to {}. LINK: {}", tableNumber, toEmail, qrLink);
        try {
            sendPlainTextEmail(toEmail,
                    "Table " + tableNumber + " Digital Access - Cafe-QR",
                    "Hello,\n\nYour digital menu access link for Table " + tableNumber + " is: " + qrLink + "\n\nUse this link to browse the menu and place orders.",
                    "QR email");
            log.info("QR email successfully sent to {}", toEmail);
        } catch (Exception e) {
            log.warn("FAILED to send QR email to {}: {}. !!! LINK IS LOGGED ABOVE !!!", toEmail, e.getMessage());
        }
    }

    private void sendPlainTextEmailViaSmtp(String toEmail, String subject, String body, String logLabel) {
        validateSmtpConfiguration();
        log.info("Sending {} to {} using SMTP", logLabel, toEmail);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);

            emailSender.send(message);
            log.info("{} successfully sent to {} using SMTP", logLabel, toEmail);
        } catch (MailException e) {
            log.error("Failed to send {} to {} using SMTP {}:{} as {}: {}",
                    logLabel,
                    toEmail,
                    safe(mailHost),
                    mailPort,
                    safe(fromEmail),
                    e.getMessage(),
                    e);
            throw new EmailDeliveryException(buildSmtpDeliveryFailureMessage(e), e);
        }
    }

    private void sendPlainTextEmailViaGmailApi(String toEmail, String subject, String body, String logLabel) {
        validateGmailApiConfiguration();
        log.info("Sending {} to {} using Gmail API", logLabel, toEmail);

        try {
            String accessToken = requestGmailAccessToken();
            String rawMessage = buildRawMimeMessage(toEmail, subject, body);
            String jsonPayload = objectMapper.writeValueAsString(Map.of("raw", rawMessage));

            HttpRequest request = HttpRequest.newBuilder(URI.create(GMAIL_SEND_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Gmail API send failed for {} to {}. Status={}, Body={}",
                        logLabel,
                        toEmail,
                        response.statusCode(),
                        response.body());
                throw new EmailDeliveryException("Unable to send verification email using Gmail API. Please check Gmail API credentials and consent.");
            }

            log.info("{} successfully sent to {} using Gmail API", logLabel, toEmail);
        } catch (IOException e) {
            log.error("Gmail API network error while sending {} to {}: {}", logLabel, toEmail, e.getMessage(), e);
            throw new EmailDeliveryException("Unable to send verification email through Gmail API. Please try again.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Gmail API send interrupted for {} to {}: {}", logLabel, toEmail, e.getMessage(), e);
            throw new EmailDeliveryException("Unable to send verification email through Gmail API. Please try again.", e);
        }
    }

    private String requestGmailAccessToken() throws IOException, InterruptedException {
        String formBody = "client_id=" + urlEncode(gmailClientId)
                + "&client_secret=" + urlEncode(gmailClientSecret)
                + "&refresh_token=" + urlEncode(gmailRefreshToken)
                + "&grant_type=refresh_token";

        HttpRequest request = HttpRequest.newBuilder(URI.create(GOOGLE_TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("Gmail OAuth token refresh failed. Status={}, Body={}", response.statusCode(), response.body());
            throw new EmailDeliveryException("Unable to authorize Gmail API email delivery. Please check Gmail client ID, secret, and refresh token.");
        }

        JsonNode root = objectMapper.readTree(response.body());
        String accessToken = root.path("access_token").asText();
        if (isBlank(accessToken)) {
            log.error("Gmail OAuth token response did not include access_token. Body={}", response.body());
            throw new EmailDeliveryException("Unable to authorize Gmail API email delivery. Access token was missing.");
        }
        return accessToken;
    }

    private String buildRawMimeMessage(String toEmail, String subject, String body) {
        String sender = !isBlank(gmailSenderEmail) ? gmailSenderEmail.trim() : fromEmail.trim();
        String mimeMessage = "From: " + sender + "\r\n"
                + "To: " + toEmail + "\r\n"
                + "Subject: " + subject + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n"
                + body;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mimeMessage.getBytes(StandardCharsets.UTF_8));
    }

    private void validateSmtpConfiguration() {
        if (isBlank(mailHost) || isBlank(fromEmail) || isBlank(mailPassword)) {
            log.error("SMTP is not fully configured. hostConfigured={}, usernameConfigured={}, passwordConfigured={}",
                    !isBlank(mailHost),
                    !isBlank(fromEmail),
                    !isBlank(mailPassword));
            throw new EmailDeliveryException("Email delivery is not configured. Please set SMTP_USERNAME and SMTP_PASSWORD on the backend.");
        }
    }

    private void validateGmailApiConfiguration() {
        if (!isGmailApiConfigured()) {
            log.error("Gmail API email is not fully configured. clientIdConfigured={}, clientSecretConfigured={}, refreshTokenConfigured={}, senderConfigured={}",
                    !isBlank(gmailClientId),
                    !isBlank(gmailClientSecret),
                    !isBlank(gmailRefreshToken),
                    !isBlank(gmailSenderEmail));
            throw new EmailDeliveryException("Gmail API email delivery is not configured. Please set GMAIL_CLIENT_ID, GMAIL_CLIENT_SECRET, GMAIL_REFRESH_TOKEN, and GMAIL_SENDER_EMAIL.");
        }
    }

    private boolean isGmailApiConfigured() {
        return !isBlank(gmailClientId)
                && !isBlank(gmailClientSecret)
                && !isBlank(gmailRefreshToken)
                && !isBlank(gmailSenderEmail);
    }

    private boolean isGmailApiProvider() {
        return PROVIDER_GMAIL_API.equalsIgnoreCase(emailProvider == null ? "" : emailProvider.trim());
    }

    private boolean shouldUseGmailApi() {
        if (isGmailApiProvider()) {
            return true;
        }

        if (isGmailApiConfigured()) {
            log.warn("Gmail API credentials are configured but EMAIL_PROVIDER is '{}'. Using Gmail API automatically for email delivery.", safe(emailProvider));
            return true;
        }

        return false;
    }

    private void logOtpForDebugging(String toEmail, String otp) {
        if (!logOtpCode) {
            return;
        }

        log.info("[OTP DEBUG] ===> TO: {} | CODE: {}", toEmail, otp);
        System.out.println("\n[OTP DEBUG] ------------------------------------------------");
        System.out.println("[OTP DEBUG] TO: " + toEmail);
        System.out.println("[OTP DEBUG] CODE: " + otp);
        System.out.println("[OTP DEBUG] ------------------------------------------------\n");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safe(String value) {
        return isBlank(value) ? "<not configured>" : value;
    }

    private String buildSmtpDeliveryFailureMessage(MailException e) {
        if (isStandardSmtpPort(mailPort) && looksLikeConnectionFailure(e)) {
            return "Unable to connect to the SMTP server. If this backend is running on Render free, SMTP ports 25, 465, and 587 are blocked. Use a paid Render instance or an email provider that supports HTTPS API delivery or SMTP on port 2525.";
        }
        return "Unable to send verification email. Please check SMTP settings and try again.";
    }

    private boolean isStandardSmtpPort(int port) {
        return port == 25 || port == 465 || port == 587;
    }

    private boolean looksLikeConnectionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("connect") || lower.contains("timed out") || lower.contains("connection refused")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
