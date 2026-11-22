package com.example.retention.privacy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensitiveEntityMentionRepository extends JpaRepository<SensitiveEntityMention, UUID> {
    List<SensitiveEntityMention> findByEntityIdOrderByMentionSeq(UUID entityId);
    List<SensitiveEntityMention> findBySegmentIdOrderByStartOffset(UUID segmentId);
    List<SensitiveEntityMention> findByEntityIdInOrderByMentionSeq(List<UUID> entityIds);
}
