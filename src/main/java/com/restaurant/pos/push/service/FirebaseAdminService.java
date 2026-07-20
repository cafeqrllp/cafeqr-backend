package com.restaurant.pos.push.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.BatchResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class FirebaseAdminService {

    @Value("${app.firebase.project-id:}")
    private String projectId;
 
    @Value("${app.firebase.client-email:}")
    private String clientEmail;
 
    @Value("${app.firebase.private-key:}")
    private String privateKey;

    @PostConstruct
    public void init() {
        try {
            if (projectId == null || projectId.isBlank() ||
                clientEmail == null || clientEmail.isBlank() ||
                privateKey == null || privateKey.isBlank()) {
                log.warn("Firebase credentials are not fully configured. Skipping Firebase Admin SDK initialization.");
                return;
            }
            if (FirebaseApp.getApps().isEmpty()) {
                String formattedPrivateKey = privateKey.replace("\\n", "\n");
                
                // Formulate the JSON format service account credentials using Jackson to ensure correct escaping
                Map<String, Object> serviceAccountMap = new java.util.HashMap<>();
                serviceAccountMap.put("type", "service_account");
                serviceAccountMap.put("project_id", projectId);
                serviceAccountMap.put("client_email", clientEmail);
                serviceAccountMap.put("private_key", formattedPrivateKey);
                serviceAccountMap.put("private_key_id", "dummy-private-key-id");
                serviceAccountMap.put("client_id", "dummy-client-id");
                serviceAccountMap.put("auth_uri", "https://accounts.google.com/o/oauth2/auth");
                serviceAccountMap.put("token_uri", "https://oauth2.googleapis.com/token");
                serviceAccountMap.put("auth_provider_x509_cert_url", "https://www.googleapis.com/oauth2/v1/certs");
                serviceAccountMap.put("client_x509_cert_url", "https://www.googleapis.com/robot/v1/metadata/x509/" + clientEmail.replace("@", "%40"));

                String serviceAccountJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(serviceAccountMap);

                InputStream serviceAccountStream = new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8));
                
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccountStream))
                        .setProjectId(projectId)
                        .build();

                FirebaseApp.initializeApp(options);
                log.info("Firebase Admin SDK successfully initialized for project: {}", projectId);
            }
        } catch (Exception e) {
            log.error("Failed to initialize Firebase Admin SDK", e);
        }
    }

    /**
     * Backward-compatible overload: sends without a specific Android notification channel.
     */
    public BatchResponse sendMulticast(String title, String body, Map<String, String> data, List<String> tokens) {
        return sendMulticast(title, body, data, tokens, null);
    }

    /**
     * Sends a multicast push notification. When channelId is provided, an AndroidConfig is
     * attached so Android 8.0+ devices route the notification to the correct channel
     * (and thus play the correct custom sound file).
     */
    public BatchResponse sendMulticast(String title, String body, Map<String, String> data, List<String> tokens, String channelId) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        try {
            MulticastMessage.Builder builder = MulticastMessage.builder()
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .putAllData(data)
                    .addAllTokens(tokens);

            // Attach Android-specific config with the notification channel for custom sounds
            if (channelId != null && !channelId.isBlank()) {
                AndroidNotification.Builder androidNotificationBuilder = AndroidNotification.builder()
                        .setChannelId(channelId);

                // For backward compatibility (Android 7.1 and below): set the sound resource name directly
                String soundName = null;
                if ("channel_delivery".equals(channelId)) soundName = "delivery";
                else if ("channel_kitchen".equals(channelId)) soundName = "kitchen";
                else if ("channel_takeaway".equals(channelId)) soundName = "takeaway";
                else if ("channel_settle".equals(channelId)) soundName = "settle";

                if (soundName != null) {
                    androidNotificationBuilder.setSound(soundName);
                }

                // Add Accept/Decline action click_action for Delivery orders
                String category = data != null ? data.get("category") : null;
                if ("DELIVERY".equalsIgnoreCase(category)) {
                    androidNotificationBuilder.setClickAction("DELIVERY_ACTIONS");
                }

                AndroidConfig androidConfig = AndroidConfig.builder()
                        .setNotification(androidNotificationBuilder.build())
                        .build();
                builder.setAndroidConfig(androidConfig);
            }

            MulticastMessage message = builder.build();

            BatchResponse response = FirebaseMessaging.getInstance().sendEachForMulticast(message);
            log.info("Successfully sent multicast message. Success count: {}, Failure count: {}",
                    response.getSuccessCount(), response.getFailureCount());
            return response;
        } catch (Exception e) {
            log.error("Failed to send Firebase multicast message", e);
            return null;
        }
    }
}

