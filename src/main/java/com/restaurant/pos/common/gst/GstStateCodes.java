package com.restaurant.pos.common.gst;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * India GST state codes (first two digits of a GSTIN) and GSTIN helpers.
 * Used only by India GST exports — never applied to non-India tenants' tax logic.
 */
public final class GstStateCodes {

    private static final Pattern GSTIN_PATTERN =
            Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("01", "Jammu and Kashmir"),
            Map.entry("02", "Himachal Pradesh"),
            Map.entry("03", "Punjab"),
            Map.entry("04", "Chandigarh"),
            Map.entry("05", "Uttarakhand"),
            Map.entry("06", "Haryana"),
            Map.entry("07", "Delhi"),
            Map.entry("08", "Rajasthan"),
            Map.entry("09", "Uttar Pradesh"),
            Map.entry("10", "Bihar"),
            Map.entry("11", "Sikkim"),
            Map.entry("12", "Arunachal Pradesh"),
            Map.entry("13", "Nagaland"),
            Map.entry("14", "Manipur"),
            Map.entry("15", "Mizoram"),
            Map.entry("16", "Tripura"),
            Map.entry("17", "Meghalaya"),
            Map.entry("18", "Assam"),
            Map.entry("19", "West Bengal"),
            Map.entry("20", "Jharkhand"),
            Map.entry("21", "Odisha"),
            Map.entry("22", "Chhattisgarh"),
            Map.entry("23", "Madhya Pradesh"),
            Map.entry("24", "Gujarat"),
            Map.entry("26", "Dadra and Nagar Haveli and Daman and Diu"),
            Map.entry("27", "Maharashtra"),
            Map.entry("29", "Karnataka"),
            Map.entry("30", "Goa"),
            Map.entry("31", "Lakshadweep"),
            Map.entry("32", "Kerala"),
            Map.entry("33", "Tamil Nadu"),
            Map.entry("34", "Puducherry"),
            Map.entry("35", "Andaman and Nicobar Islands"),
            Map.entry("36", "Telangana"),
            Map.entry("37", "Andhra Pradesh"),
            Map.entry("38", "Ladakh"),
            Map.entry("97", "Other Territory"));

    private GstStateCodes() {
    }

    public static String normalize(String gstin) {
        return gstin == null ? null : gstin.trim().toUpperCase();
    }

    public static boolean isValidGstin(String gstin) {
        String g = normalize(gstin);
        return g != null && GSTIN_PATTERN.matcher(g).matches();
    }

    /** Two-digit state code from a GSTIN, or null when the GSTIN is missing/malformed. */
    public static String stateCode(String gstin) {
        String g = normalize(gstin);
        if (g == null || g.length() < 2 || !Character.isDigit(g.charAt(0)) || !Character.isDigit(g.charAt(1))) {
            return null;
        }
        String code = g.substring(0, 2);
        return STATES.containsKey(code) ? code : null;
    }

    public static String stateName(String code) {
        return code == null ? null : STATES.get(code);
    }

    /** "27-Maharashtra" style label used by the GST portal for Place of Supply. */
    public static String label(String code) {
        String name = stateName(code);
        return name == null ? null : code + "-" + name;
    }

    /** UTGST applies instead of SGST for Union Territories without legislature. */
    public static boolean isUnionTerritoryWithoutLegislature(String code) {
        return code != null && switch (code) {
            case "04", "26", "31", "35", "38", "97" -> true;
            default -> false;
        };
    }

    /**
     * Maps free-text unit names to GST Unique Quantity Codes (UQC).
     * Returns "OTH-OTHERS" when no confident match exists so filers can review.
     */
    public static String uqc(String uom) {
        if (uom == null || uom.isBlank()) return "OTH-OTHERS";
        String u = uom.trim().toLowerCase().replace(".", "");
        return switch (u) {
            case "kg", "kgs", "kilogram", "kilograms", "kilo" -> "KGS-KILOGRAMS";
            case "g", "gm", "gms", "gram", "grams" -> "GMS-GRAMMES";
            case "mg" -> "OTH-OTHERS";
            case "l", "lt", "ltr", "ltrs", "litre", "litres", "liter", "liters" -> "LTR-LITRES";
            case "ml", "mls", "millilitre", "milliliter" -> "MLT-MILILITRE";
            case "pc", "pcs", "piece", "pieces" -> "PCS-PIECES";
            case "nos", "no", "unit", "units", "each", "ea", "number", "numbers" -> "NOS-NUMBERS";
            case "box", "boxes" -> "BOX-BOX";
            case "pack", "packs", "pkt", "pkts", "packet", "packets", "pac" -> "PAC-PACKS";
            case "btl", "bottle", "bottles" -> "BTL-BOTTLES";
            case "doz", "dozen", "dozens" -> "DOZ-DOZENS";
            case "m", "mtr", "mtrs", "metre", "metres", "meter", "meters" -> "MTR-METERS";
            case "can", "cans" -> "CAN-CANS";
            case "bag", "bags" -> "BAG-BAGS";
            case "tin", "tins" -> "TIN-TINS";
            case "set", "sets" -> "SET-SETS";
            case "roll", "rolls" -> "ROL-ROLLS";
            case "ctn", "carton", "cartons" -> "CTN-CARTONS";
            case "tray", "trays" -> "OTH-OTHERS";
            case "qtl", "quintal" -> "QTL-QUINTAL";
            case "ton", "tonne", "tonnes", "mt" -> "TON-TONNES";
            default -> "OTH-OTHERS";
        };
    }
}
