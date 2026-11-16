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

    /** How long a POS-reserved dispatch blocks server-side (text-only) sends for the same order. */
    private static final long CLIENT_DISPATCH_RESERVATION_MS = 180_000;
    /** Duplicate-send guard window. */
    private static final long DUPLICATE_WINDOW_MS = 60_000;
    /** Invoices are far smaller than this; anything bigger is rejected as suspicious. */
    private static final int MAX_PDF_BASE64_CHARS = 14 * 1024 * 1024;

    /** Orders whose WhatsApp bill the POS will send itself (with the invoice PDF). */
    private final Map<java.util.UUID, Long> clientDispatchReservations = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Called when a settle/create request says the POS will send the bill (with PDF) itself, so that the
     * async settle hook and the outbox consumer do not send a text-only bill first.
     */
    public void reserveForClientDispatch(java.util.UUID orderId) {
        if (orderId != null) {
            clientDispatchReservations.put(orderId, System.currentTimeMillis());
        }
    }

    /**
     * Asynchronously sends the digital bill to the customer's WhatsApp upon order settlement.
     */
    @Async
    public void sendOrderSettledBillAsync(Order order) {
        try {
            sendOrderSettledBill(order);
        } catch (Exception ex) {
            log.error("[WhatsAppService] Unexpected error sending bill for order {}", order != null ? order.getId() : null, ex);
        }
    }

    /**
     * Server-initiated send (settle hook / outbox consumer). Skipped when the POS has reserved the dispatch.
     */
    @Transactional(readOnly = true)
    public boolean sendOrderSettledBill(Order order) {
        if (order == null || order.getId() == null) return false;
        Long reservedAt = clientDispatchReservations.get(order.getId());
        if (reservedAt != null && System.currentTimeMillis() - reservedAt < CLIENT_DISPATCH_RESERVATION_MS) {
            log.info("[WhatsAppService] Order {} bill is reserved for POS dispatch with invoice PDF; skipping server-side send",
                    order.getId());
            return false;
        }
        return dispatchBill(order, null);
    }

    @Transactional(readOnly = true)
    public boolean sendOrderBillWithAttachment(java.util.UUID orderId, String explicitPhone, String pdfBase64) {
        return sendOrderBillWithAttachment(orderId, explicitPhone, pdfBase64, null);
    }

    /**
     * POS-initiated send: the invoice PDF generated by the POS (same document as Download / Print in Sales)
     * is attached, with the bill summary as the caption.
     */
    @Transactional(readOnly = true)
    public boolean sendOrderBillWithAttachment(java.util.UUID orderId, String explicitPhone, String pdfBase64, String filename) {
        log.info("[WhatsAppService] sendOrderBillWithAttachment called for orderId: {}, explicitPhone: {}, hasPdf: {}",
                orderId, explicitPhone, pdfBase64 != null && !pdfBase64.isBlank());
        if (orderId == null) return false;
        clientDispatchReservations.remove(orderId);
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
        return dispatchBill(order, filename);
    }

    private boolean dispatchBill(Order order, String requestedFilename) {
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

        String rawPhone = resolveCustomerPhone(order);
        if (rawPhone == null || rawPhone.isBlank()) {
            log.info("[WhatsAppService] No customer phone number for order {}, skipping WhatsApp bill", order.getId());
            return false;
        }

        Organization org = order.getOrgId() != null
                ? organizationRepository.findById(order.getOrgId()).orElse(null)
                : null;
        Client client = order.getClientId() != null
                ? clientRepository.findById(order.getClientId()).orElse(null)
                : null;

        String storeCountry = client != null ? client.getCountry() : null;
        String normalizedPhone = billFormatter.normalizePhoneNumber(rawPhone, storeCountry);
        if (normalizedPhone == null) {
            log.warn("[WhatsAppService] Invalid phone number '{}' (store country {}) for order {}, skipping WhatsApp bill",
                    rawPhone, storeCountry, order.getId());
            return false;
        }

        // Atomic claim AFTER validation, so a caller without a usable phone never blocks one that has it
        // (flag, not timestamp equality: two callers in the same millisecond must not both win)
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean won = new java.util.concurrent.atomic.AtomicBoolean(false);
        recentlySentOrders.compute(order.getId(), (k, v) -> {
            if (v != null && now - v < DUPLICATE_WINDOW_MS) return v;
            won.set(true);
            return now;
        });
        if (!won.get()) {
            log.info("[WhatsAppService] WhatsApp bill already sent recently for order {}, skipping duplicate", order.getId());
            return true;
        }

        log.info("[WhatsAppService] Preparing WhatsApp digital bill for order {}: phone={}, rawPhone={}, country={}",
                order.getId(), normalizedPhone, rawPhone, storeCountry);

        // Fetch linked invoice if available
        Invoice invoice = null;
        List<Invoice> invoices = invoiceRepository.findByOrderId(order.getId());
        if (invoices != null && !invoices.isEmpty()) {
            invoice = invoices.stream()
                    .filter(inv -> !"VOID".equalsIgnoreCase(inv.getStatus()))
                    .findFirst()
                    .orElse(invoices.get(0));
        }

        // No e-bill link: the invoice PDF is attached, and the web invoice page asks the customer to log in
        String billMessage = billFormatter.formatDigitalBill(order, invoice, org, client);
        if (billMessage.isBlank()) {
            recentlySentOrders.remove(order.getId(), now);
            log.warn("[WhatsAppService] Formatted digital bill message was empty for order {}", order.getId());
            return false;
        }

        String pdf = sanitizePdfBase64(order.getInvoicePdfBase64(), order.getId());
        String filename = pdf != null ? resolvePdfFilename(requestedFilename, invoice, order) : null;

        String branchSessionId = order.getOrgId() != null ? "org_" + order.getOrgId() : null;
        String fallbackSessionId = order.getClientId() != null ? "client_" + order.getClientId() : null;
        String primarySessionId = branchSessionId != null ? branchSessionId : fallbackSessionId;

        log.info("[WhatsAppService] Dispatching bill for order {} to session {} (fallback: {}), pdf: {}",
                order.getId(), primarySessionId, fallbackSessionId, filename);

        // "+" tells the gateway the number already has its country code (resolved from the store's country)
        boolean dispatched = dispatchToGateway(primarySessionId, fallbackSessionId, "+" + normalizedPhone,
                billMessage, pdf, filename);
        if (dispatched) {
            log.info("[WhatsAppService] Digital bill successfully sent for order {} ({})",
                    order.getId(), pdf != null ? "with invoice PDF" : "text only");
        } else {
            recentlySentOrders.remove(order.getId(), now);
            log.warn("[WhatsAppService] Digital bill dispatch failed for order {}", order.getId());
        }
        return dispatched;
    }

    /** Returns raw base64 of a real PDF, or null (the bill is then sent as text + link). */
    static String sanitizePdfBase64(String value, java.util.UUID orderId) {
        if (value == null || value.isBlank()) return null;
        String b64 = value.trim();
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) {
            b64 = b64.substring(comma + 1);
        }
        b64 = b64.replaceAll("\\s", "");
        if (b64.length() > MAX_PDF_BASE64_CHARS) {
            log.warn("[WhatsAppService] Invoice PDF for order {} too large ({} chars); sending without attachment", orderId, b64.length());
            return null;
        }
        if (!b64.startsWith("JVBER")) { // base64 of "%PDF"
            log.warn("[WhatsAppService] Attachment for order {} is not a PDF; sending without attachment", orderId);
            return null;
        }
        return b64;
    }

    static String resolvePdfFilename(String requested, Invoice invoice, Order order) {
        String name = requested != null ? requested.trim() : "";
        if (name.isEmpty()) {
            String no = invoice != null && invoice.getInvoiceNo() != null && !invoice.getInvoiceNo().isBlank()
                    ? invoice.getInvoiceNo()
                    : (order.getOrderNo() != null ? order.getOrderNo() : order.getId().toString());
            name = "Invoice-" + no;
        }
        name = name.replaceAll("[^\\w.\\-]", "_");
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            name = name + ".pdf";
        }
        return name;
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
