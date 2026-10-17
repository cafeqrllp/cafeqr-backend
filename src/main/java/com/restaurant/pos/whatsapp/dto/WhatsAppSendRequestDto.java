package com.restaurant.pos.whatsapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppSendRequestDto {
    private String sessionId;
    private String fallbackSessionId;
    private String phone;
    private String text;
    private String pdfBase64;
    private String filename;
}
