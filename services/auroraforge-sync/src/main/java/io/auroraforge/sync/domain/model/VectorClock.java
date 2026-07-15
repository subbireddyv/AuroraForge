package io.auroraforge.sync.domain.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lamport-style vector clock for tracking concurrent writes across AWS and Azure regions.
 *
 * Each node (cloud region) maintains its own logical clock counter. A VectorClock
 * is attached to every SyncRecord; the ConflictResolverService uses it to determine
 * causal ordering and detect concurrent (conflicting) writes.
 *
 * Ordering rules:
 *  - A happens-before B  : A[n] <= B[n] for all n, and A[m] < B[m] for some m
 *  - A concurrent with B : neither A happens-before B nor B happens-before A
 */
public record VectorClock(Map<String, Long> clocks) {

    public VectorClock {
        clocks = Collections.unmodifiableMap(new HashMap<>(clocks));
    }

    public static VectorClock empty() {
        return new VectorClock(Map.of());
    }

    /** Returns a new VectorClock with the given node's counter incremented by 1. */
    public VectorClock increment(String nodeId) {
        Map<String, Long> updated = new HashMap<>(clocks);
        updated.merge(nodeId, 1L, Long::sum);
        return new VectorClock(updated);
    }

    /** Merges two clocks by taking the max of each node's counter. */
    public VectorClock merge(VectorClock other) {
        Map<String, Long> merged = new HashMap<>(clocks);
        other.clocks.forEach((node, count) -> merged.merge(node, count, Math::max));
        return new VectorClock(merged);
    }

    public CausalRelation compareTo(VectorClock other) {
        boolean thisBeforeOther  = isBeforeOrEqual(this, other);
        boolean otherBeforeThis  = isBeforeOrEqual(other, this);

        if (thisBeforeOther && otherBeforeThis) return CausalRelation.EQUAL;
        if (thisBeforeOther)                    return CausalRelation.HAPPENED_BEFORE;
        if (otherBeforeThis)                    return CausalRelation.HAPPENED_AFTER;
        return CausalRelation.CONCURRENT;
    }

    private static boolean isBeforeOrEqual(VectorClock a, VectorClock b) {
        Set<String> allNodes = new java.util.HashSet<>(a.clocks.keySet());
        allNodes.addAll(b.clocks.keySet());
        return allNodes.stream().allMatch(n -> a.clocks.getOrDefault(n, 0L)
                                               <= b.clocks.getOrDefault(n, 0L));
    }

    public enum CausalRelation {
        HAPPENED_BEFORE, HAPPENED_AFTER, EQUAL, CONCURRENT
    }

    @Override
    public String toString() {
        return clocks.toString();
    }
}
