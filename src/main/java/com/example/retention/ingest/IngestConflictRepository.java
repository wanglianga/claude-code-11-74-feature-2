package com.example.retention.ingest;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestConflictRepository extends JpaRepository<IngestConflict, UUID> {
}
