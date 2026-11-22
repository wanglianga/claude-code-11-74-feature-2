package com.example.retention.disposal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationRecordRepository extends JpaRepository<EscalationRecord, UUID> {
}
