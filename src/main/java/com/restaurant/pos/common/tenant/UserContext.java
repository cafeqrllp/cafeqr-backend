package com.restaurant.pos.common.tenant;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

public class UserContext {
    private static final ThreadLocal<UserContextData> CONTEXT = new ThreadLocal<>();

    @Data
    @Builder
    public static class UserContextData {
        private UUID userId;
        private String email;
        private String role;
        private UUID clientId;
        private String clientName;
        private UUID orgId;
        private String orgName;
        private UUID terminalId;
        private String terminalName;
        private String country;
    }

    public static void setContext(UserContextData data) {
        CONTEXT.set(data);
    }

    public static UserContextData getContext() {
        UserContextData data = CONTEXT.get();
        if (data == null) {
            data = UserContextData.builder().build();
            CONTEXT.set(data);
        }
        return data;
    }

    public static void setCurrentTenant(UUID tenantId) {
        UserContextData data = getContext();
        data.setClientId(tenantId);
    }

    public static UUID getCurrentTenant() {
        return getContext().getClientId();
    }

    public static void setCurrentOrg(UUID orgId) {
        UserContextData data = getContext();
        data.setOrgId(orgId);
    }

    public static UUID getCurrentOrg() {
        return getContext().getOrgId();
    }

    public static void setCurrentTerminal(UUID terminalId) {
        UserContextData data = getContext();
        data.setTerminalId(terminalId);
    }

    public static UUID getCurrentTerminal() {
        return getContext().getTerminalId();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
