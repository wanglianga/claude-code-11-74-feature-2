package com.example.retention.ingest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingObjectRepository extends JpaRepository<RecordingObject, UUID> {
    Optional<RecordingObject> findByIdempotencyKey(String idempotencyKey);
    List<RecordingObject> findBySourceObjectIdOrderBySourceVersionDesc(String sourceObjectId);
    List<RecordingObject> findByStatus(String status);
}
