package com.example.retention.ingest;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingSegmentRepository extends JpaRepository<RecordingSegment, UUID> {
    List<RecordingSegment> findByRecordingIdOrderBySeq(UUID recordingId);
}
