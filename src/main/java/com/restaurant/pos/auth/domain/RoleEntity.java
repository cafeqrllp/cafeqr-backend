package com.restaurant.pos.auth.domain;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.Set;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "roles")
public class RoleEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", insertable = false, updatable = false)
    private Client client;

    @Column(nullable = false)
    private String name;

    private String description;

    @Builder.Default
    @Column(name = "isactive", length = 1)
    private String isactive = "Y";

    @Column(name = "can_cancel_order")
    @Builder.Default
    private Boolean canCancelOrder = true;

    @Column(name = "can_delete_order_item")
    @Builder.Default
    private Boolean canDeleteOrderItem = true;

    @Column(name = "can_decrement_order_item")
    @Builder.Default
    private Boolean canDecrementOrderItem = true;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_permissions",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_menus",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "menu_id")
    )
    private Set<Menu> menus;
}
