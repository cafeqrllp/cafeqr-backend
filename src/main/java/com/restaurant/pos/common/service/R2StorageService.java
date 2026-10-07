package com.restaurant.pos.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.UUID;

@Slf4j
@Service
public class R2StorageService {

    private final S3Client s3Client;
    private final String bucketName;
    private final String publicUrl;

    public R2StorageService(
            @Value("${cloudflare.r2.endpoint:}") String endpoint,
            @Value("${cloudflare.r2.access-key:}") String accessKey,
            @Value("${cloudflare.r2.secret-key:}") String secretKey,
            @Value("${cloudflare.r2.bucket-name:testcafeqrbucket}") String bucketName,
            @Value("${cloudflare.r2.public-url:https://pub-bf76bb80bc194145967a7fbfe9147c89.r2.dev}") String publicUrl) {

        this.bucketName = bucketName;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;

        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank() && endpoint != null && !endpoint.isBlank()) {
            this.s3Client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .region(Region.US_EAST_1)
                    .build();
            log.info("Cloudflare R2 Storage Service initialized successfully for bucket: {}", bucketName);
        } else {
            this.s3Client = null;
            log.warn("Cloudflare R2 Storage Service is not configured (missing credentials/endpoint).");
        }
    }

    public boolean isConfigured() {
        return s3Client != null;
    }

    public String uploadProductImage(byte[] imageBytes, String contentType, String extension) {
        if (!isConfigured()) {
            throw new IllegalStateException("Cloudflare R2 storage credentials are not configured on server");
        }

        String ext = (extension != null && !extension.isBlank()) ? extension.replaceAll("[^a-zA-Z0-9]", "") : "jpg";
        String filename = "products/prod_" + UUID.randomUUID() + "." + ext;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(filename)
                .contentType(contentType != null && !contentType.isBlank() ? contentType : "image/jpeg")
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(imageBytes));
        log.info("Successfully uploaded image to Cloudflare R2: {}", filename);

        return publicUrl + "/" + filename;
    }
}
