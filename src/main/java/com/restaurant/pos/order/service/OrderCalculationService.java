package com.restaurant.pos.order.service;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.order.domain.TaxType;
import com.restaurant.pos.order.dto.CalculatedLine;
import com.restaurant.pos.order.dto.CalculationLineRequest;
import com.restaurant.pos.order.dto.CalculationRequest;
import com.restaurant.pos.order.dto.CalculationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authoritative order-level financial calculation engine.
 *
 * <p>Semantic contract (applies uniformly to INCLUSIVE and EXCLUSIVE lines):
 * <ul>
 *   <li>{@code FaceAmount} — customer-visible monetary amount (tax-inclusive for inclusive lines,
 *       same as base for NONE/zero-rate items).  Always represents what the customer sees on their bill.</li>
 *   <li>{@code BaseAmount} — ex-tax taxable base used for GST computation.</li>
 *   <li>Order-level discounts are always allocated against {@code faceAfterLineDiscount} (customer-visible
 *       eligible amount per line), then the base share is derived by back-calculating through the tax factor.</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderCalculationService {

    private static final int INTERNAL_SCALE = 8;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public CalculationResult calculate(CalculationRequest request, ConfigurationDto config) {

        // ── Upfront validation ──────────────────────────────────────────────
        validateRequest(request);

        int dp = config.getCurrencyDecimalPlaces() != null ? config.getCurrencyDecimalPlaces() : 2;
        boolean gstEnabled = config.isTaxEnabled();
        boolean pricesIncludeTax = config.isPricesIncludeTax();

        // ── Resolve order discount inputs ───────────────────────────────────
        String orderDiscType = resolveDiscountType(request.getOrderDiscountType());
        BigDecimal orderDiscVal = safe(request.getOrderDiscountValue());

        if (orderDiscVal.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Order discount value cannot be negative.");
        }
        if ("PERCENT".equals(orderDiscType) && orderDiscVal.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("Order discount percentage cannot exceed 100%.");
        }

        // ── Step 1: Line-level normalisation & line discounts ───────────────
        List<CalculatedLine> calculatedLines = new ArrayList<>();
        BigDecimal defaultTaxRate = getDefaultTaxRate(config);

        for (CalculationLineRequest lineReq : request.getLines()) {
            calculatedLines.add(processLine(lineReq, gstEnabled, pricesIncludeTax, defaultTaxRate, dp));
        }

        // ── Step 2: Order-level discount distribution ───────────────────────
        // Eligible lines: those where faceAfterLineDiscount > 0
        List<CalculatedLine> eligibleLines = calculatedLines.stream()
                .filter(cl -> cl.getFaceAfterLineDiscount().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        BigDecimal totalFaceAfterLineDiscount = eligibleLines.stream()
                .map(CalculatedLine::getFaceAfterLineDiscount)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b));

        BigDecimal sumOrderDiscDisplay = BigDecimal.ZERO;

        if (orderDiscVal.compareTo(BigDecimal.ZERO) > 0
                && totalFaceAfterLineDiscount.compareTo(BigDecimal.ZERO) > 0) {

            if ("AMOUNT".equals(orderDiscType)
                    && orderDiscVal.compareTo(totalFaceAfterLineDiscount) > 0) {
                throw new BusinessException(
                        "Order discount cannot exceed the eligible total ("
                                + totalFaceAfterLineDiscount.setScale(dp, ROUNDING) + ").");
            }

            BigDecimal totalOrderDiscFace;
            if ("PERCENT".equals(orderDiscType)) {
                totalOrderDiscFace = totalFaceAfterLineDiscount
                        .multiply(orderDiscVal.divide(BigDecimal.valueOf(100), INTERNAL_SCALE, ROUNDING))
                        .setScale(dp, ROUNDING);
            } else {
                // AMOUNT — already validated ≤ totalFaceAfterLineDiscount
                totalOrderDiscFace = orderDiscVal.setScale(dp, ROUNDING);
            }

            BigDecimal allocatedFaceSum = BigDecimal.ZERO;

            for (int i = 0; i < eligibleLines.size(); i++) {
                CalculatedLine cl = eligibleLines.get(i);
                BigDecimal lineRate = cl.getTaxRate().divide(BigDecimal.valueOf(100), INTERNAL_SCALE, ROUNDING);

                BigDecimal itemDiscFace;
                if (i == eligibleLines.size() - 1) {
                    // Last eligible line absorbs the remainder to eliminate rounding drift
                    itemDiscFace = totalOrderDiscFace.subtract(allocatedFaceSum);
                } else if ("PERCENT".equals(orderDiscType)) {
                    // Direct per-line percentage: each line's share = its face × pct
                    itemDiscFace = cl.getFaceAfterLineDiscount()
                            .multiply(orderDiscVal.divide(BigDecimal.valueOf(100), INTERNAL_SCALE, ROUNDING))
                            .setScale(dp, ROUNDING);
                    allocatedFaceSum = allocatedFaceSum.add(itemDiscFace);
                } else {
                    // Proportional amount allocation by face eligible amount
                    BigDecimal shareRatio = cl.getFaceAfterLineDiscount()
                            .divide(totalFaceAfterLineDiscount, INTERNAL_SCALE, ROUNDING);
                    itemDiscFace = totalOrderDiscFace.multiply(shareRatio).setScale(dp, ROUNDING);
                    allocatedFaceSum = allocatedFaceSum.add(itemDiscFace);
                }

                // Derive base from face using the tax factor — uniform for inclusive and exclusive
                BigDecimal itemDiscBase;
                if (cl.getTaxType() == TaxType.NONE || lineRate.compareTo(BigDecimal.ZERO) == 0) {
                    itemDiscBase = itemDiscFace;
                } else {
                    itemDiscBase = itemDiscFace.divide(
                            BigDecimal.ONE.add(lineRate), INTERNAL_SCALE, ROUNDING);
                }

                cl.setAllocatedOrderDiscountBase(itemDiscBase);
                cl.setAllocatedOrderDiscountFace(itemDiscFace);
                sumOrderDiscDisplay = sumOrderDiscDisplay.add(itemDiscFace);
            }
        }

        // ── Step 3: Final aggregation ───────────────────────────────────────
        BigDecimal sumTaxable = BigDecimal.ZERO;
        BigDecimal sumTax    = BigDecimal.ZERO;
        BigDecimal sumTotal  = BigDecimal.ZERO;
        BigDecimal grossFace = BigDecimal.ZERO;
        BigDecimal sumLineDiscDisplay = BigDecimal.ZERO;

        List<CalculatedLine> finalLines = new ArrayList<>();

        for (CalculatedLine cl : calculatedLines) {
            BigDecimal lineRate = cl.getTaxRate().divide(BigDecimal.valueOf(100), INTERNAL_SCALE, ROUNDING);
            BigDecimal qty = cl.getQuantity();

            // Final base = base after line discount minus order discount base share
            BigDecimal finalBase = cl.getBaseAfterLineDiscount()
                    .subtract(cl.getAllocatedOrderDiscountBase())
                    .max(BigDecimal.ZERO);

            BigDecimal taxable;
            BigDecimal tax;
            BigDecimal total;

            if (cl.getTaxType() == TaxType.INCLUSIVE) {
                // For inclusive lines, derive taxable from the final customer-visible face total
                BigDecimal faceTotal = cl.getFaceAfterLineDiscount()
                        .subtract(cl.getAllocatedOrderDiscountFace())
                        .max(BigDecimal.ZERO)
                        .setScale(dp, ROUNDING);
                total   = faceTotal;
                taxable = cl.getTaxRate().compareTo(BigDecimal.ZERO) > 0
                        ? total.divide(BigDecimal.ONE.add(lineRate), dp, ROUNDING)
                        : total;
                tax     = total.subtract(taxable).setScale(dp, ROUNDING);
            } else {
                // EXCLUSIVE or NONE: build up from base
                taxable = finalBase.setScale(dp, ROUNDING);
                tax     = taxable.multiply(lineRate).setScale(dp, ROUNDING);
                total   = taxable.add(tax).setScale(dp, ROUNDING);
            }

            sumTaxable = sumTaxable.add(taxable);
            sumTax     = sumTax.add(tax);
            sumTotal   = sumTotal.add(total);
            grossFace  = grossFace.add(cl.getGrossFaceAmount());

            // Line discount display: always face value (customer-visible)
            sumLineDiscDisplay = sumLineDiscDisplay.add(cl.getLineDiscountFaceAmount());

            finalLines.add(CalculatedLine.builder()
                    .lineId(cl.getLineId())
                    .clientLineId(cl.getClientLineId())
                    .productId(cl.getProductId())
                    .variantId(cl.getVariantId())
                    .productName(cl.getProductName())
                    .categoryName(cl.getCategoryName())
                    .isPackagedGood(cl.getIsPackagedGood())
                    .quantity(qty)
                    .unitPrice(cl.getUnitPrice())
                    .unitPriceExTax(cl.getUnitPriceExTax().setScale(dp + 2, ROUNDING))
                    .grossBaseAmount(cl.getGrossBaseAmount().setScale(dp, ROUNDING))
                    .grossFaceAmount(cl.getGrossFaceAmount().setScale(dp, ROUNDING))
                    .grossLineAmount(cl.getGrossFaceAmount().setScale(dp, ROUNDING)) // backward compat
                    .lineDiscountInputType(cl.getLineDiscountInputType())
                    .lineDiscountInputValue(cl.getLineDiscountInputValue())
                    .lineDiscountBaseAmount(cl.getLineDiscountBaseAmount().setScale(dp, ROUNDING))
                    .lineDiscountFaceAmount(cl.getLineDiscountFaceAmount().setScale(dp, ROUNDING))
                    .baseAfterLineDiscount(cl.getBaseAfterLineDiscount().setScale(dp, ROUNDING))
                    .faceAfterLineDiscount(cl.getFaceAfterLineDiscount().setScale(dp, ROUNDING))
                    .allocatedOrderDiscountBase(cl.getAllocatedOrderDiscountBase().setScale(dp, ROUNDING))
                    .allocatedOrderDiscountFace(cl.getAllocatedOrderDiscountFace().setScale(dp, ROUNDING))
                    .taxableAmount(taxable)
                    .taxRate(cl.getTaxRate())
                    .taxAmount(tax)
                    .lineTotal(total)
                    .taxType(cl.getTaxType())
                    .taxCode(cl.getTaxCode())
                    .taxName(cl.getTaxName())
                    .build());
        }

        // ── Step 4: Round-off ───────────────────────────────────────────────
        BigDecimal roundOffAmount = computeRoundOff(request, config, sumTotal, dp);
        BigDecimal finalPayable   = sumTotal.add(roundOffAmount).setScale(dp, ROUNDING);

        // ── Step 5: Totals ──────────────────────────────────────────────────
        BigDecimal totalLineDiscBase  = finalLines.stream()
                .map(cl -> Objects.requireNonNullElse(cl.getLineDiscountBaseAmount(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b));
        BigDecimal totalOrderDiscBase = finalLines.stream()
                .map(cl -> Objects.requireNonNullElse(cl.getAllocatedOrderDiscountBase(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b));

        return CalculationResult.builder()
                .lines(finalLines)
                .grossAmount(grossFace.setScale(dp, ROUNDING))
                .lineDiscountDisplayAmount(sumLineDiscDisplay.setScale(dp, ROUNDING))
                .orderDiscountDisplayAmount(sumOrderDiscDisplay.setScale(dp, ROUNDING))
                .totalLineDiscountBase(totalLineDiscBase.setScale(dp, ROUNDING))
                .totalOrderDiscountBase(totalOrderDiscBase.setScale(dp, ROUNDING))
                .taxableAmount(sumTaxable.setScale(dp, ROUNDING))
                .totalTax(sumTax.setScale(dp, ROUNDING))
                .totalBeforeRoundOff(sumTotal.setScale(dp, ROUNDING))
                .roundOffAmount(roundOffAmount.setScale(dp, ROUNDING))
                .grandTotal(finalPayable)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Validates the top-level request and every line before any calculation. */
    private void validateRequest(CalculationRequest request) {
        if (request == null || request.getLines() == null || request.getLines().isEmpty()) {
            throw new BusinessException("Calculation request must contain at least one line.");
        }
        for (CalculationLineRequest lineReq : request.getLines()) {
            if (lineReq.getQuantity() == null || lineReq.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("Line quantity must be greater than zero.");
            }
            if (lineReq.getUnitPrice() != null
                    && lineReq.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("Line unit price cannot be negative.");
            }
            if (lineReq.getTaxRate() != null && lineReq.getTaxRate().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("Tax rate cannot be negative.");
            }
            // Resolve raw discount inputs for validation
            BigDecimal dv  = lineReq.getLineDiscountValue();
            String     dt  = lineReq.getLineDiscountType();
            if (dv == null && lineReq.getDiscountPercent() != null) { dv = lineReq.getDiscountPercent(); dt = "PERCENT"; }
            if (dv == null && lineReq.getDiscountAmount()  != null) { dv = lineReq.getDiscountAmount();  dt = "AMOUNT";  }
            if (dv != null) {
                if (dv.compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("Line discount value cannot be negative.");
                }
                if ("PERCENT".equalsIgnoreCase(dt) || "PERCENTAGE".equalsIgnoreCase(dt)) {
                    if (dv.compareTo(BigDecimal.valueOf(100)) > 0) {
                        throw new BusinessException("Line discount percentage cannot exceed 100%.");
                    }
                }
            }
        }
    }

    /**
     * Determines the tax behavior for a single line.
     *
     * <ol>
     *   <li>If GST is disabled globally → {@code NONE}.</li>
     *   <li>If the item is a packaged good → {@code INCLUSIVE} (MRP = face price).</li>
     *   <li>If the line carries an explicit {@code taxType} string → parse it.</li>
     *   <li>Fall back to the config: {@code pricesIncludeTax ? INCLUSIVE : EXCLUSIVE}.</li>
     * </ol>
     */
    private TaxType resolveTaxType(CalculationLineRequest line,
                                   boolean gstEnabled, boolean pricesIncludeTax) {
        if (!gstEnabled) return TaxType.NONE;
        if (Boolean.TRUE.equals(line.getIsPackagedGood())) return TaxType.INCLUSIVE;

        if (line.getTaxType() != null && !line.getTaxType().isBlank()) {
            try {
                return TaxType.valueOf(line.getTaxType().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("Invalid tax type: " + line.getTaxType());
            }
        }

        return pricesIncludeTax ? TaxType.INCLUSIVE : TaxType.EXCLUSIVE;
    }

    /** Processes a single line request and returns a populated {@link CalculatedLine}. */
    private CalculatedLine processLine(CalculationLineRequest lineReq,
                                       boolean gstEnabled, boolean pricesIncludeTax, BigDecimal defaultTaxRate, int dp) {
        BigDecimal qty      = lineReq.getQuantity();
        BigDecimal faceUnit = safe(lineReq.getUnitPrice());

        TaxType taxType = resolveTaxType(lineReq, gstEnabled, pricesIncludeTax);
        BigDecimal inputRate = lineReq.getTaxRate();
        BigDecimal rate = taxType == TaxType.NONE
                ? BigDecimal.ZERO
                : (inputRate != null && inputRate.compareTo(BigDecimal.ZERO) > 0 ? inputRate : defaultTaxRate);
        boolean isInclusive = taxType == TaxType.INCLUSIVE;
        BigDecimal lineRate = rate.divide(BigDecimal.valueOf(100), INTERNAL_SCALE, ROUNDING);

        // Ex-tax unit price
        BigDecimal baseUnit = (isInclusive && rate.compareTo(BigDecimal.ZERO) > 0)
                ? faceUnit.divide(BigDecimal.ONE.add(lineRate), INTERNAL_SCALE, ROUNDING)
                : faceUnit;

        // Gross amounts (before any discount)
        BigDecimal grossBase = baseUnit.multiply(qty);
        BigDecimal grossFace = isInclusive
                ? faceUnit.multiply(qty)
                : grossBase.multiply(BigDecimal.ONE.add(lineRate));

        // Resolve line discount inputs (with backward-compat fallback)
        String     dType = lineReq.getLineDiscountType();
        BigDecimal dVal  = lineReq.getLineDiscountValue();
        if (dType == null || dVal == null) {
            if (lineReq.getDiscountPercent() != null && lineReq.getDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
                dType = "PERCENT"; dVal = lineReq.getDiscountPercent();
            } else if (lineReq.getDiscountAmount() != null) {
                dType = "AMOUNT"; dVal = lineReq.getDiscountAmount();
            } else {
                dType = "AMOUNT"; dVal = BigDecimal.ZERO;
            }
        }
        dType = resolveDiscountType(dType);

        // Compute line discount base and face amounts
        BigDecimal lineDiscBase;
        BigDecimal lineDiscFace;

        if ("PERCENT".equals(dType)) {
            lineDiscBase = grossBase.multiply(dVal.divide(BigDecimal.valueOf(100), INTERNAL_SCALE, ROUNDING));
            lineDiscFace = lineDiscBase.multiply(BigDecimal.ONE.add(lineRate)); // face impact
        } else {
            // AMOUNT = customer-visible (face) discount — uniform for INCLUSIVE, EXCLUSIVE, NONE
            lineDiscFace = safe(dVal);
            if (taxType == TaxType.NONE || rate.compareTo(BigDecimal.ZERO) == 0) {
                lineDiscBase = lineDiscFace;
            } else {
                lineDiscBase = lineDiscFace.divide(BigDecimal.ONE.add(lineRate), INTERNAL_SCALE, ROUNDING);
            }
        }

        // Cap line discount at gross
        if (lineDiscBase.compareTo(grossBase) > 0) {
            lineDiscBase = grossBase;
            lineDiscFace = grossFace;
        }

        BigDecimal baseAfterLine = grossBase.subtract(lineDiscBase).max(BigDecimal.ZERO);
        // faceAfterLine is derived by stage subtraction (grossFace − lineDiscFace),
        // not by re-multiplying base, so stage semantics stay explicit and resilient
        // to future changes (cess, compound tax, price overrides).
        BigDecimal faceAfterLine = grossFace.subtract(lineDiscFace).max(BigDecimal.ZERO);

        return CalculatedLine.builder()
                .lineId(lineReq.getLineId())
                .clientLineId(lineReq.getClientLineId())
                .productId(lineReq.getProductId())
                .variantId(lineReq.getVariantId())
                .productName(lineReq.getProductName())
                .categoryName(lineReq.getCategoryName())
                .isPackagedGood(lineReq.getIsPackagedGood())
                .quantity(qty)
                .unitPrice(faceUnit)
                .unitPriceExTax(baseUnit)
                .grossBaseAmount(grossBase)
                .grossFaceAmount(grossFace)
                .grossLineAmount(grossFace) // backward compat alias
                .lineDiscountInputType(dType)
                .lineDiscountInputValue(dVal)
                .lineDiscountBaseAmount(lineDiscBase)
                .lineDiscountFaceAmount(lineDiscFace)
                .baseAfterLineDiscount(baseAfterLine)
                .faceAfterLineDiscount(faceAfterLine)
                .allocatedOrderDiscountBase(BigDecimal.ZERO)
                .allocatedOrderDiscountFace(BigDecimal.ZERO)
                .taxableAmount(baseAfterLine)  // will be updated after order discount
                .taxRate(rate)
                .taxAmount(baseAfterLine.multiply(lineRate))
                .taxType(taxType)
                .taxCode(lineReq.getTaxCode())
                .taxName(lineReq.getTaxName())
                .build();
    }

    /** Computes round-off, honouring explicit request mode before falling back to config. */
    private BigDecimal computeRoundOff(CalculationRequest request, ConfigurationDto config,
                                       BigDecimal sumTotal, int dp) {
        // Explicit mode on request takes priority; fall back to config
        String mode = request.getRoundOffMode() != null
                ? request.getRoundOffMode().toUpperCase()
                : (config.isRoundOffEnabled() && config.getRoundOffMode() != null
                   ? config.getRoundOffMode().toUpperCase()
                   : "DISABLED");

        if (!Set.of("DISABLED", "MANUAL", "AUTOMATIC").contains(mode)) {
            throw new BusinessException("Invalid round-off mode: " + mode);
        }

        boolean enabled = config.isRoundOffEnabled() || "AUTOMATIC".equals(mode) || "MANUAL".equals(mode);
        if (!enabled || "DISABLED".equals(mode)) return BigDecimal.ZERO;

        if ("MANUAL".equals(mode)) {
            if (request.getRequestedRoundOff() == null) return BigDecimal.ZERO;
            BigDecimal req = request.getRequestedRoundOff();
            if (req.abs().compareTo(BigDecimal.ONE) >= 0) {
                throw new BusinessException("Manual round-off must be within (-1.00, 1.00).");
            }
            return req;
        }

        // AUTOMATIC
        BigDecimal factor = (config.getRoundOffAutoFactor() != null
                && config.getRoundOffAutoFactor().compareTo(BigDecimal.ZERO) > 0)
                ? config.getRoundOffAutoFactor()
                : BigDecimal.ONE;
        BigDecimal rounded = sumTotal.divide(factor, 0, ROUNDING).multiply(factor);
        return rounded.subtract(sumTotal);
    }

    /** Normalises discount type strings to "PERCENT" or "AMOUNT". Rejects any other value. */
    private String resolveDiscountType(String raw) {
        if (raw == null || raw.isBlank()) return "AMOUNT";
        String normalized = raw.trim().toUpperCase();
        if ("PERCENTAGE".equals(normalized)) normalized = "PERCENT";
        if (!"PERCENT".equals(normalized) && !"AMOUNT".equals(normalized)) {
            throw new BusinessException("Invalid discount type: " + raw);
        }
        return normalized;
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal getDefaultTaxRate(ConfigurationDto config) {
        if (config == null || !config.isTaxEnabled()) {
            return BigDecimal.ZERO;
        }
        List<Object> rates = config.getTaxRates();
        if (rates == null || rates.isEmpty()) {
            return BigDecimal.ZERO;
        }
        String defaultTaxId = config.getTaxDefaultId();
        java.util.Map<String, Object> defaultRateMap = null;
        for (Object rateObj : rates) {
            if (rateObj instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> rateMap = (java.util.Map<String, Object>) rateObj;
                if (defaultTaxId != null && defaultTaxId.equals(String.valueOf(rateMap.get("id")))) {
                    defaultRateMap = rateMap;
                    break;
                }
            }
        }
        if (defaultRateMap == null && !rates.isEmpty()) {
            Object first = rates.get(0);
            if (first instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> rateMap = (java.util.Map<String, Object>) first;
                defaultRateMap = rateMap;
            }
        }
        if (defaultRateMap != null && defaultRateMap.get("value") != null) {
            try {
                return new BigDecimal(String.valueOf(defaultRateMap.get("value")));
            } catch (Exception e) {
                // ignore
            }
        }
        return BigDecimal.ZERO;
    }
}
