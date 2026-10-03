package com.restaurant.pos.hr.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class HrEmployeeDeletedEvent extends ApplicationEvent {

    private final UUID employeeId;
    private final UUID userId;
    private final String email;
    private final UUID clientId;
    private final UUID orgId;

    public HrEmployeeDeletedEvent(Object source, UUID employeeId, UUID userId, String email, UUID clientId, UUID orgId) {
        super(source);
        this.employeeId = employeeId;
        this.userId = userId;
        this.email = email;
        this.clientId = clientId;
        this.orgId = orgId;
    }
}
