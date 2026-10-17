package com.restaurant.pos.realtime;

import com.restaurant.pos.common.entity.BaseEntity;
import com.restaurant.pos.inventory.domain.StockSnapshot;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.table.domain.RestaurantTable;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * JPA listener attached to Order, RestaurantTable, StockSnapshot and Product. Every write path
 * (POS, QR, inventory, imports) goes through JPA, so one listener covers them all without
 * touching the individual services. The signal fires only after the transaction commits.
 */
public class RealtimeChangeEntityListener {

    // JPA instantiates listeners itself, so the Spring bean is handed over through a static holder.
    private static volatile RealtimeChangeService service;

    static void register(RealtimeChangeService s) {
        service = s;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void onChange(Object entity) {
        RealtimeChangeService s = service;
        if (s == null) {
            return;
        }
        UUID clientId;
        UUID orgId;
        if (entity instanceof BaseEntity e) {
            clientId = e.getClientId();
            orgId = e.getOrgId();
        } else if (entity instanceof RestaurantTable t) {
            clientId = t.getClientId();
            orgId = t.getOrgId();
        } else if (entity instanceof StockSnapshot st) {
            clientId = st.getClientId();
            orgId = st.getOrgId();
        } else if (entity instanceof Product p) {
            clientId = p.getClientId();
            orgId = p.getOrgId();
        } else {
            return;
        }
        if (clientId == null) {
            return;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    s.notifyChanged(clientId, orgId);
                }
            });
        } else {
            s.notifyChanged(clientId, orgId);
        }
    }
}
