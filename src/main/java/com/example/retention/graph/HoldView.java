package com.example.retention.graph;

/** 保全视图：scopeType ∈ SEGMENT / RECORDING / FACT / CASE，至明确解除为止。 */
public record HoldView(
        String holdKey,
        String scopeType,
        String scopeRef,
        boolean active,
        long placedSeq,
        Long releasedSeq) {
}
