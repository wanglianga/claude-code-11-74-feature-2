package com.example.retention.privacy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceViewRepository extends JpaRepository<EvidenceView, UUID> {
    Optional<EvidenceView> findByViewCode(String viewCode);
    List<EvidenceView> findByRecordingIdOrderByCreatedSeq(UUID recordingId);
    List<EvidenceView> findByRouteAndStatus(String route, String status);
    List<EvidenceView> findByRouteOrderByCreatedSeq(String route);
    List<EvidenceView> findByRevealApprovalId(UUID approvalId);
    List<EvidenceView> findByStatusOrderByCreatedSeq(String status);
}
