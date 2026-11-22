package com.example.retention.obligations;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObligationEntryRepository extends JpaRepository<ObligationEntry, UUID> {

    List<ObligationEntry> findBySegmentIdOrderByEntrySeq(UUID segmentId);

    List<ObligationEntry> findByEntrySeqLessThanEqualOrderByEntrySeq(long seq);

    /**
     * 某水位下片段的有效义务：ORIGINAL/RECTIFY 且未被同水位内的 VOID 引用。
     */
    @Query("""
            select e from ObligationEntry e
            where e.segmentId = :segmentId
              and e.entrySeq <= :watermark
              and e.entryKind in ('ORIGINAL', 'RECTIFY')
              and not exists (
                    select 1 from ObligationEntry v
                    where v.invalidatesId = e.id and v.entrySeq <= :watermark)
            order by e.entrySeq
            """)
    List<ObligationEntry> activeEntries(@Param("segmentId") UUID segmentId, @Param("watermark") long watermark);
}
