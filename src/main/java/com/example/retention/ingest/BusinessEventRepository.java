package com.example.retention.ingest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessEventRepository extends JpaRepository<BusinessEvent, UUID> {
    Optional<BusinessEvent> findByIdempotencyKey(String idempotencyKey);
    List<BusinessEvent> findBySourceEventIdOrderByEventVersionDesc(String sourceEventId);
    List<BusinessEvent> findBySeqLessThanEqualOrderBySeq(long seq);
    List<BusinessEvent> findAllByOrderBySeq();
}
