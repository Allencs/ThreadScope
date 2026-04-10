package com.threadscope.model;

import java.util.List;

/**
 * 死锁信息 — 包含检测到的所有死锁链。
 */
public record DeadlockInfo(
    List<DeadlockChain> chains
) {
    public int totalDeadlocks() {
        return chains != null ? chains.size() : 0;
    }

    public boolean hasDeadlocks() {
        return chains != null && !chains.isEmpty();
    }

    /**
     * 死锁链 — 一组互相等待形成环的线程。
     */
    public record DeadlockChain(
        List<String> threadNames,
        List<String> lockAddresses,
        String description
    ) {}
}
