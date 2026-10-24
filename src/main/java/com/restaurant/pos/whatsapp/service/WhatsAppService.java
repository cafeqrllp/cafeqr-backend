package com.restaurant.pos.whatsapp.service;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.dto.OrderCustomerDto;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import com.restaurant.pos.whatsapp.dto.WhatsAppSendRequestDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * WhatsApp integration service for asynchronous digital bill delivery.
 */
public class WhatsAppService {

    private final WhatsAppBillFormatter billFormatter;
    private final OrganizationRepository organizationRepository;
    private final ClientRepository clientRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate = createRestTemplate();
    private final RestTemplate fastRestTemplate = createFastRestTemplate();
    private final Map<java.util.UUID, Long> recentlySentOrders = new java.util.concurrent.ConcurrentHashMap<>();

    private static RestTemplate createRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4000);
        factory.setReadTimeout(12000);
        return new RestTemplate(factory);
    }

    private static RestTemplate createFastRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3500);
        return new RestTemplate(factory);
    }

    @Value("${whatsapp.gateway.url:${WHATSAPP_GATEWAY_URL:http://whatsapp-gateway:3005}}")
    private String defaultGatewayUrl;

    @Value("${app.frontend-url:${FRONTEND_URL:http://localhost:3000}}")
    private String defaultFrontendUrl;

    /**
     * Asynchronously sends the digital bill to the customer's WhatsApp upon order settlement.
     */
    @Async
    public void sendOrderSettledBillAsync(Order order) {
        try {
            sendOrderSettledBill(order, null);
        } catch (Exception ex) {
            log.error("[WhatsAppService] Unexpected error sending bill for order {}", order != null ? order.getId() : null, ex);
        }
    }

    @Transactional(readOnly = true)
    public boolean sendOrderSettledBill(Order order) {
        return sendOrderSettledBill(order, null);
    }

    @Transactional(readOnly = true)
    public boolean sendOrderSettledBill(Order order, String explicitFrontendUrl) {
        if (order == null || order.getId() == null) return false;

        try {
            if (order.getLines() == null || !org.hibernate.Hibernate.isInitialized(order.getLines())) {
                Order reloaded = orderRepository.findByIdWithLines(order.getId()).orElse(null);
                if (reloaded != null) {
                    reloaded.setInvoicePdfBase64(order.getInvoicePdfBase64());
                    if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
                        reloaded.setCustomerPhone(order.getCustomerPhone());
                    }
                    order = reloaded;
                }
            }
        } catch (Exception ex) {
            log.debug("[WhatsAppService] Could not verify line initialization: {}", ex.getMessage());
        }

        long now = System.currentTimeMillis();
        // Atomic claim: concurrent callers (frontend request + settled-event consumer) cannot both pass
        Long claimed = recentlySentOrders.compute(order.getId(),
                (k, v) -> (v != null && now - v < 60_000) ? v : now);
        if (claimed != now) {
            log.info("[WhatsAppService] WhatsApp bill already sent recently for order {}, skipping duplicate", order.getId());
            return true;
        }

        String rawPhone = resolveCustomerPhone(order);
        if (rawPhone == null || rawPhone.isBlank()) {
            log.info("[WhatsAppService] No customer phone number for order {}, skipping WhatsApp bill", order.getId());
            return false;
        }

        String normalizedPhone = billFormatter.normalizePhoneNumber(rawPhone);
        if (normalizedPhone == null) {
            log.warn("[WhatsAppService] Invalid phone number '{}' for order {}, skipping WhatsApp bill", rawPhone, order.getId());
            return false;
        }

        log.info("[WhatsAppService] Preparing WhatsApp digital bill for order {}: phone={}, rawPhone={}",
                order.getId(), normalizedPhone, rawPhone);

        Organization org = order.getOrgId() != null 
                ? organizationRepository.findById(order.getOrgId()).orElse(null) 
                : null;
        Client client = order.getClientId() != null 
                ? clientRepository.findById(order.getClientId()).orElse(null) 
                : null;

        // Fetch linked invoice if available
        Invoice invoice = null;
        List<Invoice> invoices = invoiceRepository.findByOrderId(order.getId());
        if (invoices != null && !invoices.isEmpty()) {
            invoice = invoices.stream()
                    .filter(inv -> !"VOID".equalsIgnoreCase(inv.getStatus()))
                    .findFirst()
                    .orElse(invoices.get(0));
        }

        // Construct environment-aware public invoice URL
        String baseUrl = (explicitFrontendUrl != null && !explicitFrontendUrl.isBlank())
                ? explicitFrontendUrl.trim()
                : (defaultFrontendUrl != null && !defaultFrontendUrl.isBlank() ? defaultFrontendUrl.trim() : "http://localhost:3000");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String targetId = (invoice != null && invoice.getId() != null)
                ? invoice.getId().toString()
                : order.getId().toString();
        String publicInvoiceUrl = baseUrl + "/invoice/" + targetId;

        String billMessage = billFormatter.formatZeevStyleBill(order, invoice, org, client, publicInvoiceUrl);
        if (billMessage.isBlank()) {
            log.warn("[WhatsAppService] Formatted Zeev-style bill message was empty for order {}", order.getId());
            return false;
        }

        String branchSessionId = order.getOrgId() != null ? "org_" + order.getOrgId() : null;
        String fallbackSessionId = order.getClientId() != null ? "client_" + order.getClientId() : null;
        String primarySessionId = branchSessionId != null ? branchSessionId : fallbackSessionId;

        log.info("[WhatsAppService] Dispatching bill for order {} to session {} (fallback: {}), url: {}",
                order.getId(), primarySessionId, fallbackSessionId, publicInvoiceUrl);

        // Dispatch clean, instant text bill containing the public e-bill link
        boolean dispatched = dispatchToGateway(primarySessionId, fallbackSessionId, normalizedPhone, billMessage, null, null);
        if (dispatched) {
            log.info("[WhatsAppService] Digital bill successfully sent for order {}", order.getId());
        } else {
            recentlySentOrders.remove(order.getId(), now);
            log.warn("[WhatsAppService] Digital bill dispatch failed for order {}", order.getId());
        }
        return dispatched;
    }

    @Transactional(readOnly = true)
    public boolean sendOrderBillWithAttachment(java.util.UUID orderId, String explicitPhone, String pdfBase64) {
        return sendOrderBillWithAttachment(orderId, explicitPhone, pdfBase64, null);
    }

    @Transactional(readOnly = true)
    public boolean sendOrderBillWithAttachment(java.util.UUID orderId, String explicitPhone, String pdfBase64, String explicitFrontendUrl) {
        log.info("[WhatsAppService] sendOrderBillWithAttachment called for orderId: {}, explicitPhone: {}, hasPdf: {}, frontendUrl: {}",
                orderId, explicitPhone, pdfBase64 != null && !pdfBase64.isBlank(), explicitFrontendUrl);
        if (orderId == null) return false;
        Order order = orderRepository.findByIdWithLines(orderId).orElse(null);
        if (order == null) {
            log.warn("[WhatsAppService] Order not found for id {}", orderId);
            return false;
        }
        if (pdfBase64 != null && !pdfBase64.isBlank()) {
            order.setInvoicePdfBase64(pdfBase64);
        }
        if (explicitPhone != null && !explicitPhone.isBlank()) {
            order.setCustomerPhone(explicitPhone);
        }

        Long lastSent = recentlySentOrders.get(order.getId());
        long now = System.currentTimeMillis();
        if (lastSent != null && (now - lastSent) < 15_000) {
            log.info("[WhatsAppService] Order {} was already dispatched {}ms ago, skipping concurrent re-dispatch",
                    order.getId(), (now - lastSent));
            return true;
        }
        return sendOrderSettledBill(order, explicitFrontendUrl);
    }

    public boolean dispatchToGateway(String phone, String text, String pdfBase64, String filename) {
        return dispatchToGateway(null, null, phone, text, pdfBase64, filename);
    }

    public boolean dispatchToGateway(String sessionId, String fallbackSessionId, String phone, String text, String pdfBase64, String filename) {
        long startTime = System.currentTimeMillis();
        try {
            String url = defaultGatewayUrl + "/api/send-bill";
            log.info("[WhatsAppService] Dispatching bill to Gateway URL: {} (phone: {}, session: {}, fallbackSession: {}, textLen: {}, hasPdf: {})",
                    url, phone, sessionId, fallbackSessionId, text != null ? text.length() : 0, pdfBase64 != null);

            WhatsAppSendRequestDto req = WhatsAppSendRequestDto.builder()
                    .sessionId(sessionId)
                    .fallbackSessionId(fallbackSessionId)
                    .phone(phone)
                    .text(text)
                    .pdfBase64(pdfBase64)
                    .filename(filename)
                    .build();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<WhatsAppSendRequestDto> entity = new HttpEntity<>(req, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            long elapsed = System.currentTimeMillis() - startTime;
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("[WhatsAppService] Successfully dispatched WhatsApp bill to {} in {}ms (session: {}, resp: {})",
                        phone, elapsed, sessionId, response.getBody());
                return true;
            } else {
                log.warn("[WhatsAppService] Gateway returned non-2xx status {} in {}ms: {}",
                        response.getStatusCode(), elapsed, response.getBody());
                return false;
            }
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("[WhatsAppService] Failed to send WhatsApp bill to {} in {}ms: {}", phone, elapsed, ex.getMessage());
            return false;
        }
    }

    /**
     * Dispatches an authentication verification code via the Official Cafe QR WhatsApp session.
     */
    public boolean sendOfficialAuthOtp(String phone, String otp) {
        if (phone == null || phone.isBlank() || otp == null || otp.isBlank()) {
            return false;
        }
        String cleanPhone = billFormatter.normalizePhoneNumber(phone);
        if (cleanPhone == null) {
            cleanPhone = phone.trim().replaceAll("[^0-9]", "");
        }
        String otpMessage = String.format(
                " *Cafe QR Verification Code*\n\nYour one-time login code is: *%s*\n\nValid for 5 minutes. Please do not share this code with anyone.",
                otp.trim()
        );
        log.info("[WhatsAppService] Dispatching official auth OTP to {} via session 'cafeqr_official'", cleanPhone);
        return dispatchToGateway("cafeqr_official", null, cleanPhone, otpMessage, null, null);
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    public Map<String, Object> getGatewayStatus() {
        return getGatewayStatus(null);
    }

    public Map<String, Object> getGatewayStatus(String sessionId) {
        try {
            String url = defaultGatewayUrl + "/api/status" + (sessionId != null ? "?sessionId=" + sessionId : "");
            ResponseEntity<Map<String, Object>> resp = fastRestTemplate.exchange(url, HttpMethod.GET, null, MAP_RESPONSE_TYPE);
            Map<String, Object> body = resp.getBody();
            return body != null ? body : Collections.emptyMap();
        } catch (Exception ex) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("success", false);
            fallback.put("status", "DISCONNECTED");
            fallback.put("message", "Gateway offline / unreachable: " + ex.getMessage());
            return fallback;
        }
    }

    public Map<String, Object> getGatewayQr() {
        return getGatewayQr(null);
    }

    public Map<String, Object> getGatewayQr(String sessionId) {
        try {
            String url = defaultGatewayUrl + "/api/qr" + (sessionId != null ? "?sessionId=" + sessionId : "");
            ResponseEntity<Map<String, Object>> resp = fastRestTemplate.exchange(url, HttpMethod.GET, null, MAP_RESPONSE_TYPE);
            Map<String, Object> body = resp.getBody();
            return body != null ? body : Collections.emptyMap();
        } catch (Exception ex) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("success", false);
            fallback.put("status", "DISCONNECTED");
            fallback.put("message", "Gateway offline / unreachable: " + ex.getMessage());
            return fallback;
        }
    }

    public Map<String, Object> disconnectGateway() {
        return disconnectGateway(null);
    }

    public Map<String, Object> disconnectGateway(String sessionId) {
        try {
            String url = defaultGatewayUrl + "/api/disconnect";
            Map<String, String> body = sessionId != null ? Collections.singletonMap("sessionId", sessionId) : Collections.emptyMap();
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body);
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(url, HttpMethod.POST, entity, MAP_RESPONSE_TYPE);
            Map<String, Object> bodyMap = resp.getBody();
            return bodyMap != null ? bodyMap : Collections.emptyMap();
        } catch (Exception ex) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("success", false);
            fallback.put("message", "Gateway offline / unreachable: " + ex.getMessage());
            return fallback;
        }
    }

    public Map<String, Object> sendTestMessage(String phone) {
        return sendTestMessage(null, phone);
    }

    public Map<String, Object> sendTestMessage(String sessionId, String phone) {
        try {
            String url = defaultGatewayUrl + "/api/test-message";
            Map<String, String> body = new HashMap<>();
            if (sessionId != null) body.put("sessionId", sessionId);
            if (phone != null) body.put("phone", phone);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body);
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(url, HttpMethod.POST, entity, MAP_RESPONSE_TYPE);
            Map<String, Object> bodyMap = resp.getBody();
            return bodyMap != null ? bodyMap : Collections.emptyMap();
        } catch (Exception ex) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("success", false);
            fallback.put("message", "Failed to send test message: " + ex.getMessage());
            return fallback;
        }
    }

    public Map<String, Object> clearContactSession(String sessionId, String phone) {
        try {
            String url = defaultGatewayUrl + "/api/clear-contact-session";
            Map<String, String> body = new HashMap<>();
            if (sessionId != null) body.put("sessionId", sessionId);
            if (phone != null) body.put("phone", phone);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body);
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(url, HttpMethod.POST, entity, MAP_RESPONSE_TYPE);
            Map<String, Object> bodyMap = resp.getBody();
            return bodyMap != null ? bodyMap : Collections.emptyMap();
        } catch (Exception ex) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("success", false);
            fallback.put("message", "Failed to clear contact session: " + ex.getMessage());
            return fallback;
        }
    }

    private String resolveCustomerPhone(Order order) {
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
            return order.getCustomerPhone();
        }
        if (order.getCustomers() != null && !order.getCustomers().isEmpty()) {
            for (OrderCustomerDto c : order.getCustomers()) {
                if (c != null && c.getPhone() != null && !c.getPhone().isBlank()) {
                    return c.getPhone();
                }
            }
        }
        if (order.getCustomerId() != null) {
            Customer c = customerRepository.findById(order.getCustomerId()).orElse(null);
            if (c != null && c.getPhone() != null && !c.getPhone().isBlank()) {
                return c.getPhone();
            }
        }
        // Fallback: search in description
        if (order.getDescription() != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:Phone|ph|mobile):\\s*([^|,\\n]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(order.getDescription());
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }
}
