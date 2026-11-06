package com.threadscope.jfr;

import com.threadscope.config.JfrProperties;
import com.threadscope.engine.analyzer.CodeLayerClassifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrMemoryTest {

    /** default 模板录制：只有 ObjectAllocationSample，没有 TLAB 事件。业务代码见 AllocDemo（默认包）。 */
    private static JfrDocuments.ParseResult defaults;
    /** 带 ObjectAllocationInNewTLAB 的录制。 */
    private static JfrDocuments.ParseResult tlab;

    @BeforeAll
    static void parse() {
        var properties = new JfrProperties(512, "", 14, 5000, 120, 50, 1);
        defaults = new JfrRecordingParser(properties).parse(Path.of("..", "samples", "jfr", "alloc_default.jfr"));
        tlab = new JfrRecordingParser(properties).parse(Path.of("..", "samples", "jfr", "hotmethods_before.jfr"));
    }

    @Test
    void defaultTemplateFallsBackToAllocationSamples() {
        var memory = defaults.memory();
        assertEquals(List.of(JfrMemoryCollector.SAMPLE), memory.profiles().stream().map(JfrDocuments.AllocationProfile::source).toList());
        var overview = JfrMemoryOverview.build(memory, defaults.threads());
        assertTrue(overview.findings().stream().anyMatch(f -> f.title().contains("ObjectAllocationSample")));
        assertTrue(overview.collectors().stream().mapToLong(JfrDocuments.GcSummary::count).sum() > 0);
        assertTrue(overview.heapPeak().stream().anyMatch(v -> v != null && v > 0));
        assertNotNull(overview.heapMax());
    }

    @Test
    void tlabRecordingUsesTlabFirst() {
        var sources = tlab.memory().profiles().stream().map(JfrDocuments.AllocationProfile::source).toList();
        assertEquals(JfrMemoryCollector.TLAB, sources.getFirst());
        var profile = tlab.memory().profiles().getFirst();
        var stacks = profile.stacks();
        assertEquals(stacks.totalSamples(), stacks.stacks().stream().mapToLong(JfrDocuments.StackSample::samples).sum());
        assertEquals(stacks.totalSamples(), stacks.bucketSamples().stream().mapToLong(Long::longValue).sum());
        assertTrue(stacks.stacks().stream().allMatch(s -> s.type() != null && s.type() >= 0 && s.type() < profile.types().size()));
    }

    /** 业务口径：JDK 里的 byte[] / StringBuilder 分配都归到 AllocDemo 的方法上，两个方法加起来覆盖几乎全部分配。 */
    @Test
    void businessBasisAttributesJdkAllocationsToCallingCode() {
        var profile = defaults.memory().profiles().getFirst();
        var index = JfrStackIndex.of(profile.stacks());
        var list = JfrAllocationQuery.list(profile, index, "", "business", 20, null, null, CodeLayerClassifier.inferring());
        assertEquals("business", list.sort());
        var labels = list.methods().stream().map(JfrDocuments.AllocationRow::label).toList();
        var top = list.methods().getFirst();
        assertTrue(top.label().startsWith("AllocDemo."), labels.toString());
        double attributed = list.methods().stream().filter(r -> r.label().startsWith("AllocDemo."))
            .mapToDouble(r -> r.attributedPercent()).sum();
        assertTrue(attributed > 95, labels + " " + attributed);
        assertTrue(top.sites().stream().anyMatch(site -> site.type() != null));
        long sum = list.methods().stream().mapToLong(JfrDocuments.AllocationRow::attributedBytes).sum();
        assertEquals(list.totalBytes(), sum + list.unattributedBytes());
    }

    @Test
    void typeFilterAndTimeRangePartitionBytes() {
        var profile = defaults.memory().profiles().getFirst();
        var index = JfrStackIndex.of(profile.stacks());
        var classifier = CodeLayerClassifier.inferring();
        var all = JfrAllocationQuery.list(profile, index, "", "self", 1000, null, null, classifier);
        String byteArray = all.types().getFirst().name();
        var onlyType = JfrAllocationQuery.list(profile, index, "", "self", 1000, null, byteArray, classifier);
        assertEquals(all.types().getFirst().bytes(), onlyType.totalBytes());
        assertEquals(byteArray, onlyType.type());
        assertTrue(onlyType.methods().stream().allMatch(r -> r.sites().stream().allMatch(s -> byteArray.equals(s.type()))));

        int n = profile.stacks().bucketCount();
        var first = JfrAllocationQuery.list(profile, index, "", "self", 1000, new JfrDocuments.BucketRange(0, n / 2), null, classifier);
        var second = JfrAllocationQuery.list(profile, index, "", "self", 1000, new JfrDocuments.BucketRange(n / 2, n), null, classifier);
        assertEquals(all.totalBytes(), first.totalBytes() + second.totalBytes());

        var missing = JfrAllocationQuery.list(profile, index, "", "self", 10, null, "no.such.Type", classifier);
        assertEquals(0, missing.totalBytes());
        assertTrue(missing.methods().isEmpty());
        assertNull(all.unattributedBytes());
    }

    @Test
    void expandingAnAttributedRowFollowsTheSamePath() {
        var profile = defaults.memory().profiles().getFirst();
        var index = JfrStackIndex.of(profile.stacks());
        var classifier = CodeLayerClassifier.inferring();
        var row = JfrAllocationQuery.list(profile, index, "", "business", 5, null, null, classifier).methods().getFirst();
        var callers = JfrAllocationQuery.expand(profile, index, List.of(row.methodKey()), "business", "", null, null, classifier);
        assertEquals(row.callers().stream().map(JfrDocuments.HotspotCaller::methodKey).toList(),
            callers.stream().map(JfrDocuments.HotspotCaller::methodKey).toList());
        assertEquals(row.attributedBytes(), callers.stream().mapToLong(JfrDocuments.HotspotCaller::samples).sum());
    }

    @Test
    void arrayDescriptorsBecomeJavaTypeNames() {
        assertEquals("byte[]", JfrMemoryCollector.typeName("[B"));
        assertEquals("java.lang.String[][]", JfrMemoryCollector.typeName("[[Ljava/lang/String;"));
        assertEquals("java.lang.String", JfrMemoryCollector.typeName("java.lang.String"));
    }
}
