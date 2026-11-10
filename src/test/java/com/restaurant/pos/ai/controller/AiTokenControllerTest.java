package com.restaurant.pos.ai.controller;

import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.auth.service.JwtService;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.service.SystemConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Settings > "Enable MCP" decides whether AI tokens can be issued at all. */
class AiTokenControllerTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SystemConfigurationService config = mock(SystemConfigurationService.class);
    private final Authentication auth = mock(Authentication.class);
    private final UUID clientId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private AiTokenController controller;

    @BeforeEach
    void setUp() {
        controller = new AiTokenController(jwtService, users, config);
        ReflectionTestUtils.setField(controller, "aiTokenExpirationMs", 1_800_000L);
        User user = mock(User.class);
        when(user.getClientId()).thenReturn(clientId);
        when(user.getOrgId()).thenReturn(orgId);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(auth.getName()).thenReturn("owner@cafe.test");
        when(users.findByEmail("owner@cafe.test")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(anyMap(), any(), anyLong())).thenReturn("ai-token");
    }

    private void mcpEnabled(boolean enabled) {
        ConfigurationDto dto = new ConfigurationDto();
        dto.setMcpEnabled(enabled);
        when(config.getConfigurationForClientAndBranch(clientId, orgId)).thenReturn(dto);
    }

    @Test
    void refusesWhenMcpIsTurnedOff() {
        mcpEnabled(false);
        ResponseEntity<?> res = controller.issueAiToken(auth);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        verify(jwtService, never()).generateToken(anyMap(), any(), anyLong());
    }

    @Test
    void issuesReadOnlyTokenWhenMcpIsOn() {
        mcpEnabled(true);
        ResponseEntity<?> res = controller.issueAiToken(auth);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().toString()).contains("ai-token").contains(JwtService.AI_READ_SCOPE);
    }
}
