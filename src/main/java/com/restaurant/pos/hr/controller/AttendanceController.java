package com.restaurant.pos.hr.controller;

import com.restaurant.pos.hr.dto.AttendanceDto;
import com.restaurant.pos.hr.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hr/attendance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
public class AttendanceController {

    private final AttendanceService attendanceService;

    @GetMapping
    public ResponseEntity<List<AttendanceDto>> getAllAttendance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(attendanceService.getAllAttendanceRecords(startDate, endDate));
    }

    @PostMapping("/clock-in")
    public ResponseEntity<AttendanceDto> clockInBody(@RequestBody AttendanceDto dto) {
        String method = dto.getPunchMethod() != null ? dto.getPunchMethod() : "MANUAL";
        return ResponseEntity.ok(attendanceService.clockIn(dto.getEmployeeId(), method));
    }

    @PostMapping("/clock-in/{employeeId}")
    public ResponseEntity<AttendanceDto> clockIn(
            @PathVariable UUID employeeId,
            @RequestParam(defaultValue = "MANUAL") String punchMethod) {
        return ResponseEntity.ok(attendanceService.clockIn(employeeId, punchMethod));
    }

    @PostMapping("/clock-out")
    public ResponseEntity<AttendanceDto> clockOutBody(@RequestBody AttendanceDto dto) {
        return ResponseEntity.ok(attendanceService.clockOut(dto.getEmployeeId()));
    }

    @PostMapping("/clock-out/{employeeId}")
    public ResponseEntity<AttendanceDto> clockOut(@PathVariable UUID employeeId) {
        return ResponseEntity.ok(attendanceService.clockOut(employeeId));
    }

    @PostMapping("/manual")
    public ResponseEntity<AttendanceDto> createManualAttendance(@RequestBody AttendanceDto dto) {
        return ResponseEntity.ok(attendanceService.saveManualAttendance(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AttendanceDto> updateAttendance(
            @PathVariable UUID id,
            @RequestBody AttendanceDto dto) {
        dto.setId(id);
        return ResponseEntity.ok(attendanceService.saveManualAttendance(dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAttendance(@PathVariable UUID id) {
        attendanceService.deleteAttendanceRecord(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<AttendanceDto>> getAttendanceForEmployee(
            @PathVariable UUID employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(attendanceService.getAttendanceByEmployeeAndDateRange(employeeId, startDate, endDate));
    }

    @GetMapping("/status/{employeeId}")
    public ResponseEntity<java.util.Map<String, String>> getEmployeeStatus(@PathVariable UUID employeeId) {
        String status = attendanceService.getEmployeeCurrentStatus(employeeId);
        return ResponseEntity.ok(java.util.Map.of("status", status));
    }
}
