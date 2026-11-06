package com.threadscope.config;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用与生产相同的滚动策略（按大小切分并压缩）验证 Log4j2 能写出归档文件。
 * 生产配置的阈值是 50MB / 按天，这里把大小降到 2KB，避免测试写几十兆。
 */
class LogRollingTest {

    @Test
    void rollsAndCompressesWhenFileExceedsSize() throws Exception {
        Path dir = Files.createTempDirectory("threadscope-log-roll");
        Path config = dir.resolve("log4j2-test.xml");
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Configuration status="WARN">
                <Appenders>
                    <RollingFile name="RollingFile"
                                 fileName="%s/threadscope.log"
                                 filePattern="%s/archive/threadscope-%%d{yyyy-MM-dd}-%%i.log.gz">
                        <PatternLayout pattern="%%d{yyyy-MM-dd HH:mm:ss.SSS} %%-5level %%msg%%n"/>
                        <Policies>
                            <SizeBasedTriggeringPolicy size="2 KB"/>
                        </Policies>
                        <DefaultRolloverStrategy max="20" fileIndex="min"/>
                    </RollingFile>
                </Appenders>
                <Loggers>
                    <Root level="info">
                        <AppenderRef ref="RollingFile"/>
                    </Root>
                </Loggers>
            </Configuration>
            """.formatted(dir, dir);
        Files.writeString(config, xml);

        LoggerContext context = (LoggerContext) Configurator.initialize(null, config.toAbsolutePath().toString());
        try {
            var log = context.getLogger("roll");
            String line = "x".repeat(180);
            for (int i = 0; i < 30; i++) {
                log.info("line {} {}", i, line);
            }
        } finally {
            context.stop();
        }

        assertTrue(Files.exists(dir.resolve("threadscope.log")), "当前日志文件应存在");
        try (var archived = Files.list(dir.resolve("archive"))) {
            assertTrue(archived.anyMatch(path -> path.getFileName().toString().endsWith(".log.gz")),
                "超过大小后应压缩归档");
        }
    }
}
