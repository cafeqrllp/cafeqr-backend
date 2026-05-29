package com.restaurant.pos.common.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/debug")
public class DeploymentInfoController {

    private static final Instant STARTED_AT = Instant.now();

    private final Environment environment;

    public DeploymentInfoController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/deployment-info")
    public Map<String, Object> deploymentInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("serviceName", env("RENDER_SERVICE_NAME"));
        info.put("gitCommit", env("RENDER_GIT_COMMIT"));
        info.put("gitBranch", env("RENDER_GIT_BRANCH"));
        info.put("gitRepoSlug", env("RENDER_GIT_REPO_SLUG"));
        info.put("render", env("RENDER"));
        info.put("instanceId", env("RENDER_INSTANCE_ID"));
        info.put("externalUrl", env("RENDER_EXTERNAL_URL"));
        info.put("profiles", String.join(",", environment.getActiveProfiles()));
        info.put("startedAt", STARTED_AT.toString());
        return info;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logDeploymentInfo() {
        Map<String, Object> info = deploymentInfo();
        log.info(
                "deployment_info serviceName={} gitCommit={} gitBranch={} gitRepoSlug={} render={} instanceId={} profiles={} startedAt={}",
                info.get("serviceName"),
                info.get("gitCommit"),
                info.get("gitBranch"),
                info.get("gitRepoSlug"),
                info.get("render"),
                info.get("instanceId"),
                info.get("profiles"),
                info.get("startedAt")
        );
    }

    private String env(String key) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
