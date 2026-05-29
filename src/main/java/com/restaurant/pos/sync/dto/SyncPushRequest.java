package com.restaurant.pos.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncPushRequest {
    private String clientVersion;
    private Integer schemaVersion;

    @Builder.Default
    private List<SyncOperationRequest> operations = new ArrayList<>();
}
