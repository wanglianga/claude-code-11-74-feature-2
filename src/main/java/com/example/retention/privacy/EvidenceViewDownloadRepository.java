package com.example.retention.privacy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceViewDownloadRepository extends JpaRepository<EvidenceViewDownload, UUID> {
    List<EvidenceViewDownload> findByViewIdOrderBySeq(UUID viewId);
    List<EvidenceViewDownload> findAllByOrderBySeq();
}
