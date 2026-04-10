package com.threadscope.engine.parser;

import com.threadscope.engine.pattern.DumpPatterns;
import com.threadscope.model.*;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;

/**
 * Thread Dump 有限状态机词法分析器。
 *
 * 核心设计思想：
 * 1. 流式逐行扫描，永不将全文加载到内存
 * 2. 使用有限状态机 (FSM) 精确切分线程块边界
 * 3. 每个线程块独立解析，支持并行化
 *
 * 状态转换：
 *   INITIAL → THREAD_HEADER → THREAD_STATE → STACK_TRACE → OWNABLE_SYNC → (循环)
 *   任意状态遇到 "Found...deadlock" → DEADLOCK_SECTION
 */
public class ThreadDumpLexer {

    /**
     * 词法分析器的内部状态
     */
    private enum LexerState {
        INITIAL,            // 等待 Dump 开始
        THREAD_HEADER,      // 刚解析到线程头部行
        THREAD_STATE,       // 正在解析线程状态行
        STACK_TRACE,        // 正在解析堆栈帧
        OWNABLE_SYNC,       // 正在解析可拥有同步器
        DEADLOCK_SECTION    // 正在解析死锁段
    }

    /**
     * 原始线程块 — 词法分析的产出物
     */
    public record RawThreadBlock(
        String headerLine,
        String stateLine,
        List<String> bodyLines,
        List<String> ownableSyncLines
    ) {}

    /**
     * 词法分析结果
     */
    public record LexerResult(
        String jvmVersion,
        List<RawThreadBlock> threadBlocks,
        List<String> deadlockSection
    ) {}

    /**
     * 从 InputStream 流式解析 Thread Dump 文件。
     * 这是主入口方法，支持大文件流式处理。
     *
     * @param inputStream Dump 文件输入流
     * @return 词法分析结果
     */
    public LexerResult tokenize(InputStream inputStream) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return tokenize(reader);
        }
    }

    /**
     * 从字符串内容解析 (用于粘贴场景)。
     */
    public LexerResult tokenize(String content) throws IOException {
        try (var reader = new BufferedReader(new StringReader(content))) {
            return tokenize(reader);
        }
    }

    /**
     * 核心状态机 — 逐行扫描并切分线程块。
     */
    private LexerResult tokenize(BufferedReader reader) throws IOException {
        String jvmVersion = null;
        List<RawThreadBlock> threadBlocks = new ArrayList<>();
        List<String> deadlockLines = new ArrayList<>();

        LexerState state = LexerState.INITIAL;

        // 当前正在构建的线程块
        String currentHeader = null;
        String currentStateLine = null;
        List<String> currentBody = new ArrayList<>();
        List<String> currentOwnableSync = new ArrayList<>();
        boolean inDeadlockSection = false;
        boolean inSmrSection = false;

        String line;
        while ((line = reader.readLine()) != null) {

            // ─── 跳过 "Threads class SMR info:" 段 (JDK 17+) ───
            if (line.startsWith("Threads class SMR info:")) {
                inSmrSection = true;
                continue;
            }
            if (inSmrSection) {
                // SMR段以 "}" 结尾或空行结束
                if (line.contains("}") || DumpPatterns.BLANK_LINE.matcher(line).matches()) {
                    if (line.contains("}")) {
                        inSmrSection = false;
                    }
                }
                continue;
            }

            // ─── 全局检测：死锁段开始 ───
            if (DumpPatterns.DEADLOCK_HEADER.matcher(line).find()) {
                // 先保存当前线程块
                flushCurrentThread(threadBlocks, currentHeader, currentStateLine, currentBody, currentOwnableSync);
                currentHeader = null;
                currentStateLine = null;
                currentBody = new ArrayList<>();
                currentOwnableSync = new ArrayList<>();

                inDeadlockSection = true;
                deadlockLines.add(line);
                state = LexerState.DEADLOCK_SECTION;
                continue;
            }

            // ─── 死锁段内容收集 ───
            if (inDeadlockSection) {
                // 死锁段结束条件: 遇到新的线程头 或 "JNI global refs" 等
                if (DumpPatterns.THREAD_HEADER.matcher(line).matches()) {
                    inDeadlockSection = false;
                    state = LexerState.INITIAL;
                    // 回退到正常处理 (fall through)
                } else {
                    deadlockLines.add(line);
                    continue;
                }
            }

            // ─── Dump 头部检测 ───
            Matcher headerMatcher = DumpPatterns.DUMP_HEADER.matcher(line);
            if (headerMatcher.matches()) {
                jvmVersion = headerMatcher.group(1);
                continue;
            }

            // ─── 跳过已知的非线程段 (JNI, VM mutexes 等) ───
            if (line.startsWith("JNI global ref") || line.startsWith("JNI weak ref") ||
                line.startsWith("VM Mutex/Monitor") || line.startsWith("Heap")) {
                // 保存当前线程块，进入跳过模式
                flushCurrentThread(threadBlocks, currentHeader, currentStateLine, currentBody, currentOwnableSync);
                currentHeader = null;
                currentStateLine = null;
                currentBody = new ArrayList<>();
                currentOwnableSync = new ArrayList<>();
                state = LexerState.INITIAL;
                continue;
            }

            // ─── 线程头部行检测 ───
            Matcher threadHeaderMatcher = DumpPatterns.THREAD_HEADER.matcher(line);
            if (threadHeaderMatcher.matches()) {
                // 保存上一个线程块
                flushCurrentThread(threadBlocks, currentHeader, currentStateLine, currentBody, currentOwnableSync);

                // 开始新线程块
                currentHeader = line;
                currentStateLine = null;
                currentBody = new ArrayList<>();
                currentOwnableSync = new ArrayList<>();
                state = LexerState.THREAD_HEADER;
                continue;
            }

            // ─── 简化版线程头部 (兼容性) ───
            // 以双引号开头且包含 nid= 的行，尝试作为线程头
            if (line.startsWith("\"") && line.contains("nid=")) {
                flushCurrentThread(threadBlocks, currentHeader, currentStateLine, currentBody, currentOwnableSync);
                currentHeader = line;
                currentStateLine = null;
                currentBody = new ArrayList<>();
                currentOwnableSync = new ArrayList<>();
                state = LexerState.THREAD_HEADER;
                continue;
            }

            // ─── 在线程块内部 ───
            if (currentHeader != null) {

                // 线程状态行
                if (DumpPatterns.THREAD_STATE.matcher(line).find()) {
                    currentStateLine = line;
                    state = LexerState.THREAD_STATE;
                    continue;
                }

                // Ownable Synchronizers 段头部
                if (DumpPatterns.OWNABLE_SYNC_HEADER.matcher(line).find()) {
                    state = LexerState.OWNABLE_SYNC;
                    continue;
                }

                // Ownable Synchronizers 内容
                if (state == LexerState.OWNABLE_SYNC) {
                    if (DumpPatterns.OWNABLE_SYNC_ENTRY.matcher(line).find()) {
                        currentOwnableSync.add(line.trim());
                        continue;
                    }
                    if (DumpPatterns.OWNABLE_SYNC_NONE.matcher(line).find()) {
                        continue;
                    }
                    // 空行或其他内容 → 结束 ownable sync 段
                    if (DumpPatterns.BLANK_LINE.matcher(line).matches()) {
                        continue;
                    }
                }

                // 堆栈帧 或 锁操作行
                if (line.startsWith("\t") || line.startsWith("   ")) {
                    currentBody.add(line);
                    if (state == LexerState.THREAD_HEADER || state == LexerState.THREAD_STATE) {
                        state = LexerState.STACK_TRACE;
                    }
                    continue;
                }

                // 空行 — 可能是线程块之间的分隔
                if (DumpPatterns.BLANK_LINE.matcher(line).matches()) {
                    continue;
                }
            }
        }

        // 处理最后一个线程块
        flushCurrentThread(threadBlocks, currentHeader, currentStateLine, currentBody, currentOwnableSync);

        return new LexerResult(jvmVersion, threadBlocks, deadlockLines);
    }

    /**
     * 将当前构建中的线程块保存到列表中。
     */
    private void flushCurrentThread(
            List<RawThreadBlock> blocks,
            String header,
            String stateLine,
            List<String> body,
            List<String> ownableSync) {
        if (header != null) {
            blocks.add(new RawThreadBlock(header, stateLine, List.copyOf(body), List.copyOf(ownableSync)));
        }
    }
}
