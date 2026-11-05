package com.threadscope.engine.parser;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/**
 * Dump 文件编码探测与解码。
 *
 * 常见的非 UTF-8 来源：
 * - Windows PowerShell 的 `jstack pid > dump.txt` 默认写出 UTF-16 LE (带 BOM)
 * - 中文 Windows / 老系统的 GBK 编码 (线程名、类名中含中文时乱码)
 *
 * 探测顺序：BOM → 无 BOM 的 UTF-16 特征 (大量 0x00 交错) → 严格 UTF-8 → GB18030 (GBK 超集)。
 */
public final class DumpTextDecoder {

    private DumpTextDecoder() {}

    public static final String AUTO = "auto";
    private static final Charset GB18030 = Charset.forName("GB18030");

    /**
     * @param text    解码后的文本
     * @param charset 实际使用的编码
     * @param fallback 是否不是 UTF-8 (需要提示用户)
     */
    public record Decoded(String text, String charset, boolean fallback) {}

    /**
     * @param requestedCharset 用户指定的编码；null / "auto" 时自动探测
     */
    public static Decoded decode(byte[] bytes, String requestedCharset) {
        if (requestedCharset != null && !requestedCharset.isBlank() && !AUTO.equalsIgnoreCase(requestedCharset)) {
            Charset cs = Charset.forName(requestedCharset.trim());
            return new Decoded(stripBom(new String(bytes, cs)), cs.name(), !StandardCharsets.UTF_8.equals(cs));
        }

        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), "UTF-8", false);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), "UTF-16LE", true);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), "UTF-16BE", true);
        }

        Charset utf16 = sniffUtf16WithoutBom(bytes);
        if (utf16 != null) {
            return new Decoded(new String(bytes, utf16), utf16.name(), true);
        }

        String utf8 = tryDecodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return new Decoded(utf8, "UTF-8", false);
        }
        return new Decoded(new String(bytes, GB18030), "GB18030", true);
    }

    /** ASCII 为主的文本以 UTF-16 存储时，偶数或奇数位几乎全是 0x00 */
    private static Charset sniffUtf16WithoutBom(byte[] bytes) {
        int sample = Math.min(bytes.length, 4096) & ~1;
        if (sample < 64) return null;
        int zeroEven = 0;
        int zeroOdd = 0;
        for (int i = 0; i < sample; i += 2) {
            if (bytes[i] == 0) zeroEven++;
            if (bytes[i + 1] == 0) zeroOdd++;
        }
        int pairs = sample / 2;
        if (zeroOdd > pairs * 0.7 && zeroEven < pairs * 0.1) return StandardCharsets.UTF_16LE;
        if (zeroEven > pairs * 0.7 && zeroOdd < pairs * 0.1) return StandardCharsets.UTF_16BE;
        return null;
    }

    private static String tryDecodeStrict(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = decoder.decode(ByteBuffer.wrap(bytes));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }
}
