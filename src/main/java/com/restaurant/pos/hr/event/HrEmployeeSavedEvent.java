package com.restaurant.pos.hr.event;

import com.restaurant.pos.hr.dto.EmployeeDto;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class HrEmployeeSavedEvent extends ApplicationEvent {

    private final EmployeeDto employeeDto;
    private final UUID clientId;
    private final UUID orgId;
    private final String actionType; // CREATED, UPDATED, DEACTIVATED

    public HrEmployeeSavedEvent(Object source, EmployeeDto employeeDto, UUID clientId, UUID orgId, String actionType) {
        super(source);
        this.employeeDto = employeeDto;
        this.clientId = clientId;
        this.orgId = orgId;
        this.actionType = actionType;
    }
}
