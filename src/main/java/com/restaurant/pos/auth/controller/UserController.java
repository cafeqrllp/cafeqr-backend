package com.restaurant.pos.auth.controller;

import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.domain.RoleEntity;
import com.restaurant.pos.auth.repository.MenuRepository;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.DuplicateResourceException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import com.restaurant.pos.auth.domain.Menu;
import com.restaurant.pos.auth.dto.UserProfileDTO;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository repository;
    private final MenuRepository menuRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<User>>> getUsers() {
        // Include users with null clientId to handle "orphans" or global users from before migration
        List<User> users = repository.findAll().stream()
                .filter(u -> u.getClientId() == null || u.getClientId().equals(TenantContext.getCurrentTenant()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<User>> createUser(@RequestBody User user) {
        validateNewUser(user);
        user.setFirstName(user.getFirstName().trim());
        user.setLastName(user.getLastName().trim());
        user.setEmail(normalizeEmail(user.getEmail()));
        user.setPassword(passwordEncoder.encode(user.getPassword().trim()));
        if (user.getClientId() == null) {
            user.setClientId(TenantContext.getCurrentTenant());
        }
        user.setIsactive("Y");
        if (user.getIsEnabled() == null) {
            user.setIsEnabled(true);
        }
        return ResponseEntity.ok(ApiResponse.success(repository.save(user)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<User>> updateUser(@PathVariable UUID id, @RequestBody User user) {
        User existingUser = repository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Retain existing password unless an explicit new one was provided
        if (user.getPassword() != null && !user.getPassword().trim().isEmpty()) {
            existingUser.setPassword(passwordEncoder.encode(user.getPassword()));
        }

        // Update other fields
        if (!hasText(user.getFirstName())) {
            throw new BusinessException("First name is required.");
        }
        if (!hasText(user.getLastName())) {
            throw new BusinessException("Last name is required.");
        }
        if (!hasText(user.getEmail())) {
            throw new BusinessException("Email is required.");
        }
        if (user.getRoleEntity() == null || user.getRoleEntity().getId() == null) {
            throw new BusinessException("Auth role is required.");
        }
        String normalizedEmail = normalizeEmail(user.getEmail());
        repository.findByEmail(normalizedEmail)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DuplicateResourceException("A staff account with this email already exists.");
                });

        existingUser.setFirstName(user.getFirstName().trim());
        existingUser.setLastName(user.getLastName().trim());
        existingUser.setEmail(normalizedEmail);
        existingUser.setPhone(user.getPhone());
        existingUser.setRoleEntity(user.getRoleEntity());
        // Allow orgId to be null/empty for Global Access
        if (user.getOrgId() != null && !user.getOrgId().toString().isEmpty()) {
            existingUser.setOrgId(user.getOrgId());
        } else {
            existingUser.setOrgId(null);
        }

        existingUser.setTerminalId(user.getTerminalId());
        existingUser.setIsactive(user.getIsactive());

        return ResponseEntity.ok(ApiResponse.success(repository.save(existingUser)));
    }

    private void validateNewUser(User user) {
        if (user == null) {
            throw new BusinessException("Staff account details are required.");
        }
        if (!hasText(user.getFirstName())) {
            throw new BusinessException("First name is required.");
        }
        if (!hasText(user.getLastName())) {
            throw new BusinessException("Last name is required.");
        }
        if (!hasText(user.getEmail())) {
            throw new BusinessException("Email is required.");
        }
        if (user.getRoleEntity() == null || user.getRoleEntity().getId() == null) {
            throw new BusinessException("Auth role is required.");
        }
        if (!hasText(user.getPassword())) {
            throw new BusinessException("Password is required for new staff accounts.");
        }
        if (repository.existsByEmail(normalizeEmail(user.getEmail()))) {
            throw new DuplicateResourceException("A staff account with this email already exists.");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }


    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileDTO>> getMyProfile() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = repository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        
        UserProfileDTO dto = UserProfileDTO.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRoleEntity() != null ? user.getRoleEntity().getName() : "UNKNOWN")
                .build();

        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    @GetMapping("/menus")
    public ResponseEntity<ApiResponse<List<Menu>>> getMyMenus() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = repository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        
        if (user.getRoleEntity() == null || user.getRoleEntity().getMenus() == null) {
            return ResponseEntity.ok(ApiResponse.success(Collections.emptyList()));
        }
        
        List<Menu> roleMenus = user.getRoleEntity().getMenus().stream()
                .filter(m -> "Y".equalsIgnoreCase(m.getIsactive()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(roleMenus));
    }
}
