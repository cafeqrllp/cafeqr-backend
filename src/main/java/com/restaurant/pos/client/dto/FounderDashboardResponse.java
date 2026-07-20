package com.restaurant.pos.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FounderDashboardResponse {

    private Stats stats;
    private List<ClientSummaryDto> clients;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stats {
        private int totalClients;
        private int activeClients;
        private int trialClients;
        private int expiredClients;
        private int unpaidClients;
        private long totalRevenuePaise;
    }
}
