package com.threadscope.engine.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 dump 头部的 VM 描述中提取 JDK 主版本。
 *
 * JDK 9+ 的 VM 版本号即 JDK 版本 ("21.0.6+8-LTS-188")；
 * JDK 8 及更早的 HotSpot 版本号自成体系 ("25.202-b08" = JDK 8, "24.80-b11" = JDK 7)，需特殊换算。
 */
public final class JvmVersion {

    private JvmVersion() {}

    private static final Pattern LEGACY_HOTSPOT = Pattern.compile("(?:^|\\()(\\d+)\\.\\d+-b\\d+");
    private static final Pattern MODERN = Pattern.compile("(?:^|\\()(\\d+)(?=[.+\\-\\s)]|$)");

    /** 无法识别时返回 0 */
    public static int majorVersion(String vmDescription) {
        if (vmDescription == null || vmDescription.isBlank()) return 0;
        Matcher legacy = LEGACY_HOTSPOT.matcher(vmDescription);
        if (legacy.find()) {
            int hs = Integer.parseInt(legacy.group(1));
            return hs >= 25 ? 8 : 7;
        }
        Matcher m = MODERN.matcher(vmDescription.trim());
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v >= 9 && v < 100) return v;
        }
        return 0;
    }
}
