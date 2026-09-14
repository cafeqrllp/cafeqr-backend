package com.restaurant.pos.hr.entity;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "hr_punch_segments")
@Getter
@Setter
@NoArgsConstructor
public class PunchSegment extends BaseEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_id", nullable = false)
    private Attendance attendance;

    @Column(name = "clock_in_time", nullable = false)
    private LocalDateTime clockInTime;

    @Column(name = "clock_out_time")
    private LocalDateTime clockOutTime;

    @Column(name = "hours_worked", precision = 5, scale = 2)
    private BigDecimal hoursWorked = BigDecimal.ZERO;

    @Column(name = "segment_type", length = 20)
    private String segmentType = "WORK"; // e.g. WORK, BREAK
}
