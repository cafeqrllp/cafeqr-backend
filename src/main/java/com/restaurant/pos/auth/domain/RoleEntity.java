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

    @Column(name = "can_print_order")
    @Builder.Default
    private Boolean canPrintOrder = true;

    /** Manual "No Sale" drawer open from the POS. */
    @Column(name = "can_open_cash_drawer")
    @Builder.Default
    private Boolean canOpenCashDrawer = false;

    /** Drawer kicks automatically when this role settles a drawer-opening tender. */
    @Column(name = "can_auto_open_cash_drawer")
    @Builder.Default
    private Boolean canAutoOpenCashDrawer = true;

    /** Access to the cash drawer history / audit report. */
    @Column(name = "can_view_cash_drawer_history")
    @Builder.Default
    private Boolean canViewCashDrawerHistory = false;

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
