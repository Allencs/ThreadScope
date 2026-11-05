package com.threadscope.engine.analyzer;

import com.threadscope.model.AnalysisResult;
import com.threadscope.model.DataQualityIssue;
import com.threadscope.model.DataQualityIssue.Level;
import com.threadscope.model.ThreadInfo;
import com.threadscope.model.VirtualThreadReport;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据质量检查 — 输入缺陷会让部分分析静默失效 (如没用 jstack -l 时 JUC 死锁检测形同虚设)，
 * 必须在报告里明确告诉用户哪些结论受限、如何补抓。
 */
public class DataQualityInspector {

    /**
     * @param format                 dump 格式 (AnalysisResult.FORMAT_*)
     * @param threads                本快照线程
     * @param hasLockedSynchronizers 是否出现过 "Locked ownable synchronizers" 段
     * @param stateInferred          线程状态是否由栈顶推断
     * @param parseFailures          解析失败的线程块数
     * @param dumpCount              本次上传的快照总数
     * @param jvmRestartSuspected    快照之间是否疑似发生了 JVM 重启
     * @param charset                实际解码使用的编码
     * @param charsetConverted       是否从非 UTF-8 编码转换
     * @param jvmMajorVersion        JVM 主版本 (0 = 未知)
     * @param virtualThreads         虚拟线程报告
     */
    public record Context(
        String format,
        List<ThreadInfo> threads,
        boolean hasLockedSynchronizers,
        boolean stateInferred,
        int parseFailures,
        int dumpCount,
        boolean jvmRestartSuspected,
        String charset,
        boolean charsetConverted,
        int jvmMajorVersion,
        VirtualThreadReport virtualThreads
    ) {}

    public List<DataQualityIssue> inspect(Context ctx) {
        List<DataQualityIssue> issues = new ArrayList<>();
        boolean jstack = AnalysisResult.FORMAT_JSTACK.equals(ctx.format());

        if (ctx.threads().isEmpty()) {
            issues.add(new DataQualityIssue("NO_THREADS", Level.WARNING,
                "未识别到任何线程",
                "所有分析结果均为空。",
                "请确认文件是 jstack、jcmd <pid> Thread.print 或 jcmd <pid> Thread.dump_to_file 的输出；"
                    + "kill -3 的输出位于应用的标准输出日志 (如 catalina.out) 中。"));
            return issues;
        }

        if (ctx.parseFailures() > 0) {
            issues.add(new DataQualityIssue("PARSE_FAILURES", Level.WARNING,
                ctx.parseFailures() + " 个线程块解析失败",
                "这些线程未参与任何分析，统计数量与锁关系可能不完整。",
                "检查 dump 是否被截断或混入了其他日志内容。"));
        }

        if (jstack && !ctx.hasLockedSynchronizers()) {
            issues.add(new DataQualityIssue("NO_LOCKED_SYNCHRONIZERS", Level.WARNING,
                "dump 未包含 \"Locked ownable synchronizers\" 段 (未使用 jstack -l)",
                "ReentrantLock、ReadWriteLock 等 JUC 锁的持有者未知：JUC 死锁检测无法工作，"
                    + "锁等待图中 park 在这些锁上的线程找不到持有者，根因定位不完整。",
                "使用 jstack -l <pid> 或 jcmd <pid> Thread.print -l 重新抓取。"));
        }

        boolean noCpu = ctx.threads().stream().filter(t -> !t.isVirtual()).allMatch(t -> t.cpuTime() == null);
        if (jstack && noCpu) {
            issues.add(new DataQualityIssue("NO_CPU_TIME", Level.INFO,
                "dump 不含 cpu= / elapsed= 字段 (JDK 8 或更早版本的格式)",
                "多 dump 的 CPU 差值、线程频繁创建 (churn) 检测与 JVM 重启识别不可用。",
                "同时抓取 top -H -p <pid> 的输出，在 Overview 的 CPU (top -H) 标签页做关联。"));
        }

        if (ctx.dumpCount() <= 1) {
            issues.add(new DataQualityIssue("SINGLE_DUMP", Level.INFO,
                "只有一份快照",
                "单个 dump 是瞬间快照，无法区分线程是\"正好在等\"还是\"一直卡住\"，栈不动检测与 CPU 差值不可用。",
                "间隔 5~10 秒连续抓取 3 份并一起上传：for i in 1 2 3; do jstack -l <pid> > dump-$i.txt; sleep 5; done"));
        }

        if (ctx.jvmRestartSuspected()) {
            issues.add(new DataQualityIssue("JVM_RESTARTED", Level.WARNING,
                "快照之间 JVM 疑似发生过重启",
                "同名线程的存活时间变短，说明不是同一个 JVM 进程，栈不动检测与 CPU 差值已停用以免误判。",
                "确认上传的 dump 来自同一个进程 (同一 PID、同一次启动)。"));
        }

        if (ctx.stateInferred()) {
            issues.add(new DataQualityIssue("STATE_INFERRED", Level.INFO,
                "线程状态由栈顶推断",
                "JDK 21 的 jcmd Thread.dump_to_file 导出不含线程状态与锁信息，状态分布为近似值，锁分析不可用。",
                "如需锁分析，可同时抓取一份 jstack -l 并一起上传对照。"));
        }

        VirtualThreadReport vt = ctx.virtualThreads();
        if (jstack && vt != null && vt.mountedCount() > 0) {
            issues.add(new DataQualityIssue("VIRTUAL_THREADS_HIDDEN", Level.INFO,
                "应用使用了虚拟线程，但 jstack 只能看到正在挂载的虚拟线程",
                "未挂载 (阻塞/等待中) 的虚拟线程不在 dump 中，虚拟线程总量与等待点统计不完整。",
                "使用 jcmd <pid> Thread.dump_to_file -format=json /tmp/threads.json 导出全部虚拟线程后上传。"));
        }

        if (ctx.charsetConverted()) {
            issues.add(new DataQualityIssue("ENCODING_CONVERTED", Level.INFO,
                "文件编码为 " + ctx.charset() + "，已自动转换",
                "如果线程名或类名仍有乱码，说明编码识别有误。",
                "上传时可在请求参数 charset 中指定编码 (如 GBK、UTF-16LE)。"));
        }

        return issues;
    }
}
