package com.example.retention.disposal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisposalCandidateRepository extends JpaRepository<DisposalCandidate, UUID> {
    List<DisposalCandidate> findByPlanId(UUID planId);
    List<DisposalCandidate> findByRecordingId(UUID recordingId);
    List<DisposalCandidate> findByRecordingIdAndStatusIn(UUID recordingId, List<String> statuses);
    List<DisposalCandidate> findByStatus(String status);
}
