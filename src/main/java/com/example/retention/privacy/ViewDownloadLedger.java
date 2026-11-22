package com.example.retention.privacy;

import com.example.retention.common.WatermarkService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视图下载留痕写入器：以独立事务（REQUIRES_NEW）落库，
 * 即使下载因拦截在主事务中抛出异常回滚，放行/拦截留痕仍持久可审计。
 */
@Component
public class ViewDownloadLedger {

    private final EvidenceViewDownloadRepository downloadRepository;
    private final WatermarkService watermark;

    public ViewDownloadLedger(EvidenceViewDownloadRepository downloadRepository,
                              WatermarkService watermark) {
        this.downloadRepository = downloadRepository;
        this.watermark = watermark;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EvidenceViewDownload record(UUID viewId, String route, boolean granted,
                                       String role, String purpose, String reason) {
        EvidenceViewDownload ledger = new EvidenceViewDownload();
        ledger.setId(UUID.randomUUID());
        ledger.setViewId(viewId);
        ledger.setRoute(route);
        ledger.setGranted(granted);
        ledger.setRequesterRole(role);
        ledger.setPurpose(purpose);
        ledger.setReason(reason);
        ledger.setSeq(watermark.next());
        ledger.setAt(Instant.now());
        return downloadRepository.save(ledger);
    }
}
