package com.example.retention.privacy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensitiveEntityRepository extends JpaRepository<SensitiveEntity, UUID> {
    List<SensitiveEntity> findByRecordingIdOrderByRegisteredSeq(UUID recordingId);
    List<SensitiveEntity> findByRecordingIdAndStatusOrderByRegisteredSeq(UUID recordingId, String status);
    List<SensitiveEntity> findByStatusOrderByRegisteredSeq(String status);
    List<SensitiveEntity> findByRecordingIdAndEntityTypeOrderByRevision(UUID recordingId, String entityType);
}
