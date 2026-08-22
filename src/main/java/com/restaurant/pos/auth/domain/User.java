package com.restaurant.pos.auth.domain;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.domain.Terminal;
import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseEntity implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String firstName;
    private String lastName;

    @Column(unique = true, nullable = false)
    private String email;

    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    @Column(nullable = false)
    private String password;

    private String phone;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private RoleEntity roleEntity;

    @Column(name = "terminal_id")
    private UUID terminalId;

    @Column(name = "org_id")
    private UUID orgId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", insertable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terminal_id", insertable = false, updatable = false)
    private Terminal terminal;

    @Builder.Default
    @Column(name = "isactive", length = 1)
    @com.fasterxml.jackson.annotation.JsonProperty("isactive")
    private String isactive = "Y"; // Standardized Y/N

    @Builder.Default
    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled = true;

    @Column(name = "terms_accepted_version")
    private String termsAcceptedVersion;

    @Column(name = "terms_accepted_at")
    private java.time.LocalDateTime termsAcceptedAt;

    @Column(name = "terms_accepted_ip")
    private String termsAcceptedIp;

    @Override
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<SimpleGrantedAuthority> authorities = new HashSet<>();
        if (roleEntity != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleEntity.getName().toUpperCase()));
            if (roleEntity.getPermissions() != null) {
                authorities.addAll(roleEntity.getPermissions().stream()
                    .map(p -> new SimpleGrantedAuthority(p.getName().toUpperCase()))
                    .collect(Collectors.toList()));
            }
        }
        return authorities;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return isEnabled && "Y".equalsIgnoreCase(isactive);
    }
}
