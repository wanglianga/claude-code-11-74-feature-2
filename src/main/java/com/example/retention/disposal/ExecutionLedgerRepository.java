package com.example.retention.disposal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionLedgerRepository extends JpaRepository<ExecutionLedger, UUID> {
    Optional<ExecutionLedger> findByOperationNo(String operationNo);
    Optional<ExecutionLedger> findByCandidateId(UUID candidateId);
}
