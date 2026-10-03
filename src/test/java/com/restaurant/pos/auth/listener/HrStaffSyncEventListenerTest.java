package com.restaurant.pos.auth.listener;

import com.restaurant.pos.auth.domain.RoleEntity;
import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.repository.RoleRepository;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.hr.dto.EmployeeDto;
import com.restaurant.pos.hr.event.HrEmployeeDeletedEvent;
import com.restaurant.pos.hr.event.HrEmployeeSavedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HrStaffSyncEventListenerTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private PasswordEncoder passwordEncoder;
    private HrStaffSyncEventListener listener;

    private UUID clientId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        listener = new HrStaffSyncEventListener(userRepository, roleRepository, passwordEncoder);

        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();

        when(passwordEncoder.encode(any())).thenReturn("encodedPassword");
    }

    @Test
    void handleEmployeeSaved_NewActiveEmployee_ProvisionsUserWithStaffRole() {
        EmployeeDto dto = EmployeeDto.builder()
                .firstName("Alice")
                .lastName("Smith")
                .email("alice.smith@example.com")
                .phoneNumber("9876543210")
                .isActive(true)
                .build();

        RoleEntity staffRole = new RoleEntity();
        staffRole.setName("STAFF");

        when(userRepository.findByEmail("alice.smith@example.com")).thenReturn(Optional.empty());
        when(roleRepository.findByNameAndClientId("STAFF", clientId)).thenReturn(Optional.of(staffRole));

        HrEmployeeSavedEvent event = new HrEmployeeSavedEvent(this, dto, clientId, orgId, "CREATED");
        listener.handleEmployeeSaved(event);

        verify(userRepository, times(1)).save(argThat(user ->
                user.getEmail().equals("alice.smith@example.com") &&
                user.getFirstName().equals("Alice") &&
                user.getLastName().equals("Smith") &&
                user.getIsactive().equals("Y") &&
                user.getIsEnabled() &&
                user.getClientId().equals(clientId)
        ));
    }

    @Test
    void handleEmployeeSaved_ExistingUser_UpdatesUserDetailsAndStatus() {
        EmployeeDto dto = EmployeeDto.builder()
                .firstName("Alice")
                .lastName("Johnson")
                .email("alice.smith@example.com")
                .phoneNumber("5551234567")
                .isActive(false)
                .build();

        User existingUser = User.builder()
                .id(UUID.randomUUID())
                .firstName("Alice")
                .lastName("Smith")
                .email("alice.smith@example.com")
                .isactive("Y")
                .isEnabled(true)
                .build();

        when(userRepository.findByEmail("alice.smith@example.com")).thenReturn(Optional.of(existingUser));

        HrEmployeeSavedEvent event = new HrEmployeeSavedEvent(this, dto, clientId, orgId, "UPDATED");
        listener.handleEmployeeSaved(event);

        verify(userRepository, times(1)).save(argThat(user ->
                user.getLastName().equals("Johnson") &&
                user.getIsactive().equals("N") &&
                !user.getIsEnabled()
        ));
    }

    @Test
    void handleEmployeeDeleted_DeactivatesUserAccount() {
        User existingUser = User.builder()
                .id(UUID.randomUUID())
                .email("alice.smith@example.com")
                .isactive("Y")
                .isEnabled(true)
                .build();

        when(userRepository.findByEmail("alice.smith@example.com")).thenReturn(Optional.of(existingUser));

        HrEmployeeDeletedEvent event = new HrEmployeeDeletedEvent(this, UUID.randomUUID(), existingUser.getId(), "alice.smith@example.com", clientId, orgId);
        listener.handleEmployeeDeleted(event);

        verify(userRepository, times(1)).save(argThat(user ->
                user.getIsactive().equals("N") &&
                !user.getIsEnabled()
        ));
    }
}
