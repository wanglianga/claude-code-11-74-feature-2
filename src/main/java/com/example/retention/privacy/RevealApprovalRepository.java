package com.example.retention.privacy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevealApprovalRepository extends JpaRepository<RevealApproval, UUID> {
    Optional<RevealApproval> findByApprovalNo(String approvalNo);
    List<RevealApproval> findByRecordingIdOrderByCreatedSeq(UUID recordingId);
    List<RevealApproval> findByStatusOrderByCreatedSeq(String status);
}
