package com.example.retention.ingest;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldClassificationRepository extends JpaRepository<FieldClassification, UUID> {
    List<FieldClassification> findBySegmentId(UUID segmentId);
    List<FieldClassification> findBySegmentIdIn(List<UUID> segmentIds);
}
