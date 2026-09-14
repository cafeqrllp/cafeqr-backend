package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.PunchSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PunchSegmentRepository extends JpaRepository<PunchSegment, UUID> {
    
    @Query("SELECT p FROM PunchSegment p WHERE p.attendance.id = :attendanceId ORDER BY p.clockInTime ASC")
    List<PunchSegment> findByAttendanceId(@Param("attendanceId") UUID attendanceId);

    @Query("SELECT p FROM PunchSegment p WHERE p.attendance.id = :attendanceId AND p.clockOutTime IS NULL")
    Optional<PunchSegment> findOpenSegmentByAttendanceId(@Param("attendanceId") UUID attendanceId);
}
