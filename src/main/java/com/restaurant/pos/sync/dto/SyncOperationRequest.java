package com.restaurant.pos.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncOperationRequest {
    private String id;
    private String operationId;
    private String dependsOnOperationId;
    private String clientRequestId;
    private String offlineId;
    private String method;
    private String url;
    private String path;
    private Map<String, Object> params;
    private String entity;
    private Object payload;
    private Instant createdAt;
}
