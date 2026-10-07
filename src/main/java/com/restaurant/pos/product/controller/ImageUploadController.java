package com.restaurant.pos.product.controller;

import com.restaurant.pos.common.service.R2StorageService;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class ImageUploadController {

    private final R2StorageService storageService;
    private final ProductRepository productRepository;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getUploadStatus() {
        List<Product> products = productRepository.findAll();
        long totalProducts = products.size();
        long r2MigratedCount = 0;
        long remainingBase64Count = 0;
        long noImageCount = 0;

        for (Product p : products) {
            String url = p.getImageUrl();
            if (url == null || url.isBlank()) {
                noImageCount++;
            } else if (url.startsWith("data:image/")) {
                remainingBase64Count++;
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                r2MigratedCount++;
            }
        }

        boolean fullyMigrated = (remainingBase64Count == 0);

        return ResponseEntity.ok(Map.of(
                "r2Configured", storageService.isConfigured(),
                "totalProducts", totalProducts,
                "r2MigratedCount", r2MigratedCount,
                "remainingBase64Count", remainingBase64Count,
                "noImageCount", noImageCount,
                "fullyMigrated", fullyMigrated
        ));
    }

    @PostMapping("/product-image")
    public ResponseEntity<Map<String, Object>> uploadProductImage(@RequestParam("file") MultipartFile file) {
        if (!storageService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "message", "Cloud Storage (R2) is not configured on server"));
        }

        try {
            String filename = file.getOriginalFilename();
            String ext = (filename != null && filename.contains(".")) ? filename.substring(filename.lastIndexOf(".") + 1) : "jpg";
            
            String imageUrl = storageService.uploadProductImage(file.getBytes(), file.getContentType(), ext);
            return ResponseEntity.ok(Map.of("success", true, "imageUrl", imageUrl));
        } catch (Exception ex) {
            log.error("Failed to upload image to R2", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Image upload failed: " + ex.getMessage()));
        }
    }

    @PostMapping("/migrate-base64-images")
    public ResponseEntity<Map<String, Object>> migrateBase64Images() {
        if (!storageService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "message", "Cloud Storage (R2) is not configured on server"));
        }

        List<Product> products = productRepository.findAll();
        int migrated = 0;
        int skipped = 0;
        int failed = 0;

        for (Product p : products) {
            String rawUrl = p.getImageUrl();
            if (rawUrl != null && rawUrl.startsWith("data:image/")) {
                try {
                    int commaIdx = rawUrl.indexOf(",");
                    if (commaIdx > -1) {
                        String mimePart = rawUrl.substring(5, commaIdx); // e.g. image/jpeg;base64
                        String mimeType = mimePart.contains(";") ? mimePart.substring(0, mimePart.indexOf(";")) : mimePart;
                        String ext = mimeType.contains("/") ? mimeType.substring(mimeType.indexOf("/") + 1) : "jpg";
                        String base64Data = rawUrl.substring(commaIdx + 1);

                        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
                        String cdnUrl = storageService.uploadProductImage(imageBytes, mimeType, ext);
                        p.setImageUrl(cdnUrl);
                        productRepository.save(p);
                        migrated++;
                    }
                } catch (Exception e) {
                    log.error("Failed to migrate Base64 image for product ID: {}", p.getId(), e);
                    failed++;
                }
            } else {
                skipped++;
            }
        }

        log.info("Base64 migration complete: migrated={}, skipped={}, failed={}", migrated, skipped, failed);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "migrated", migrated,
                "skipped", skipped,
                "failed", failed
        ));
    }
}
