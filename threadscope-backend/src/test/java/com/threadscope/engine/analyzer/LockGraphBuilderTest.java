package com.threadscope.engine.analyzer;

import com.threadscope.model.LockAction;
import com.threadscope.model.ThreadKind;
import com.threadscope.model.ThreadState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LockGraphBuilderTest {

    private final LockGraphBuilder builder = new LockGraphBuilder();

    @Test
    void heldLockWithoutWaitersIsHiddenFromContentionButLookupFindsHolder() {
        var holder = TestThreads.thread(
            "reserve-vt", "#60", ThreadState.WAITING, null, null,
            List.of(new LockAction.Held(
                "com.acme.order.client.InventoryClient@1a2b3c",
                "com.acme.order.client.InventoryClient",
                4)),
            ThreadKind.VIRTUAL,
            "java.lang.VirtualThread.parkOnCarrierThread",
            "com.acme.order.client.InventoryClient.reserve");

        assertTrue(builder.buildLockGraph(List.of(holder)).isEmpty(), "没有等待者的锁不进入竞争列表");

        var found = builder.lookup(List.of(holder), "com.acme.order.client.InventoryClient@1a2b3c");
        assertNotNull(found);
        assertEquals("reserve-vt", found.holderThreadName());
        assertEquals("#60", found.holderThreadKey());
        assertEquals(0, found.contentionLevel());
    }
}
