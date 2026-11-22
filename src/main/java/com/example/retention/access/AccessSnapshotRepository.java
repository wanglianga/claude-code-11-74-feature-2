package com.example.retention.access;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessSnapshotRepository extends JpaRepository<AccessSnapshot, UUID> {
}
