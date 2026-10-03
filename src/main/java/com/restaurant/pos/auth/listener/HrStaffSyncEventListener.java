package com.restaurant.pos.auth.listener;

import com.restaurant.pos.auth.domain.RoleEntity;
import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.repository.RoleRepository;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.hr.dto.EmployeeDto;
import com.restaurant.pos.hr.event.HrEmployeeDeletedEvent;
import com.restaurant.pos.hr.event.HrEmployeeSavedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class HrStaffSyncEventListener {

    private static final Logger log = LoggerFactory.getLogger(HrStaffSyncEventListener.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @EventListener
    @Transactional
    public void handleEmployeeSaved(HrEmployeeSavedEvent event) {
        EmployeeDto dto = event.getEmployeeDto();
        if (dto.getEmail() == null || dto.getEmail().isBlank()) {
            return;
        }

        String email = dto.getEmail().trim().toLowerCase();
        Optional<User> existingUserOpt = userRepository.findByEmail(email);

        if (existingUserOpt.isPresent()) {
            User user = existingUserOpt.get();
            if (dto.getFirstName() != null && !dto.getFirstName().isBlank()) {
                user.setFirstName(dto.getFirstName().trim());
            }
            if (dto.getLastName() != null) {
                user.setLastName(dto.getLastName().trim());
            }
            if (dto.getPhoneNumber() != null && !dto.getPhoneNumber().isBlank()) {
                user.setPhone(dto.getPhoneNumber().trim());
            }
            if (event.getOrgId() != null) {
                user.setOrgId(event.getOrgId());
            }
            user.setIsactive(dto.isActive() ? "Y" : "N");
            user.setIsEnabled(dto.isActive());
            userRepository.save(user);
            log.info("Synced updated HR employee '{}' to Staff & Permissions User ID {}", email, user.getId());
        } else if (dto.isActive()) {
            // Auto-provision new staff user
            RoleEntity staffRole = roleRepository.findByNameAndClientId("STAFF", event.getClientId())
                    .or(() -> roleRepository.findByNameAndClientIdIsNull("STAFF"))
                    .or(() -> roleRepository.findByName("STAFF"))
                    .orElse(null);

            User user = User.builder()
                    .firstName(dto.getFirstName() != null ? dto.getFirstName().trim() : "")
                    .lastName(dto.getLastName() != null ? dto.getLastName().trim() : "")
                    .email(email)
                    .phone(dto.getPhoneNumber())
                    .password(passwordEncoder.encode("Staff@123"))
                    .roleEntity(staffRole)
                    .orgId(event.getOrgId())
                    .isactive("Y")
                    .isEnabled(true)
                    .build();
            user.setClientId(event.getClientId());

            userRepository.save(user);
            log.info("Auto-provisioned new HR employee '{}' into Staff & Permissions", email);
        }
    }

    @EventListener
    @Transactional
    public void handleEmployeeDeleted(HrEmployeeDeletedEvent event) {
        if (event.getEmail() != null && !event.getEmail().isBlank()) {
            String email = event.getEmail().trim().toLowerCase();
            userRepository.findByEmail(email).ifPresent(user -> {
                user.setIsactive("N");
                user.setIsEnabled(false);
                userRepository.save(user);
                log.info("Deactivated Staff & Permissions account for deleted HR employee '{}'", email);
            });
        }
    }
}
