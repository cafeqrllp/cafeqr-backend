package com.restaurant.pos.integration.ondc.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Data
@Component
public class OndcProperties {

    @Value("${ondc.subscriber-id:app.cafeqr.in}")
    private String subscriberId;

    @Value("${ondc.ukid:72ff234c-42d1-40e3-8a63-f8e645cbd51d}")
    private String ukid;

    @Value("${ondc.signing-private-key:ejK74mGgDxdKgok/OoJNQVvmsqjnXjf8hw7peZl2Vdt+yy1bKpgGeQkT/ggwq/PHtlZwIeQM2kHiLKlc6I8s2g==}")
    private String signingPrivateKey;

    @Value("${ondc.bpp-uri:https://test-api.cafeqr.in/api/v1/ondc}")
    private String bppUri;
}
