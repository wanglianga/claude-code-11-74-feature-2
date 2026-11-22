package com.example.retention.obligations;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerMaintenanceLogRepository extends JpaRepository<LedgerMaintenanceLog, UUID> {
}
