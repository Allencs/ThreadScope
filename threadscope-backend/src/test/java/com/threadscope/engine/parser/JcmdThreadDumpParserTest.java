package com.threadscope.engine.parser;

import com.threadscope.model.ThreadKind;
import com.threadscope.model.ThreadState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JcmdThreadDumpParserTest {

    private final JcmdThreadDumpParser parser = new JcmdThreadDumpParser(50_000);

    private static final String JSON = """
        {
          "threadDump": {
            "processId": "4242",
            "time": "2026-11-05T13:00:00.000Z",
            "runtimeVersion": "21.0.2+13-58",
            "threadContainers": [
              {
                "container": "<root>",
                "parent": null,
                "owner": null,
                "threads": [
                  { "tid": "1", "name": "main", "stack": [
                      "java.base\\/java.lang.Thread.sleep0(Native Method)",
                      "java.base\\/java.lang.Thread.sleep(Thread.java:509)",
                      "app\\/\\/com.acme.Main.main(Main.java:10)"
                  ] }
                ],
                "threadCount": "1"
              },
              {
                "container": "java.util.concurrent.ThreadPerTaskExecutor@5a07e868",
                "parent": "<root>",
                "owner": null,
                "threads": [
                  { "tid": "29", "name": "", "stack": [
                      "java.base\\/jdk.internal.misc.Unsafe.park(Native Method)",
                      "java.base\\/java.lang.VirtualThread.parkOnCarrierThread(VirtualThread.java:661)",
                      "java.base\\/java.lang.VirtualThread.park(VirtualThread.java:593)",
                      "app\\/\\/com.acme.Cache.refresh(Cache.java:42)",
                      "java.base\\/java.lang.VirtualThread.run(VirtualThread.java:309)"
                  ] }
                ],
                "threadCount": "1"
              }
            ]
          }
        }
        """;

    @Test
    void parsesJsonDumpWithVirtualThreads() {
        assertEquals(JcmdThreadDumpParser.Format.JSON, JcmdThreadDumpParser.detect(JSON));
        var dump = parser.parse(JSON, JcmdThreadDumpParser.Format.JSON);

        assertEquals("21.0.2+13-58", dump.jvmVersion());
        assertEquals(21, JvmVersion.majorVersion(dump.jvmVersion()));
        assertTrue(dump.stateInferred());
        assertEquals(2, dump.threads().size());

        var main = dump.threads().getFirst();
        assertEquals("#1", main.key());
        assertEquals(ThreadState.TIMED_WAITING, main.state());
        assertEquals("com.acme.Main", main.stackTrace().get(2).className());

        var vt = dump.threads().get(1);
        assertEquals(ThreadKind.VIRTUAL, vt.kind());
        assertEquals("VirtualThread[#29]", vt.name());
        assertEquals("java.util.concurrent.ThreadPerTaskExecutor@5a07e868", vt.container());
        assertEquals(ThreadState.WAITING, vt.state());
    }

    @Test
    void parsesTextDump() {
        String text = """
            4242
            2026-11-05T13:00:00.000Z
            21+35-2513

            #1 "main"
                  java.base/java.lang.Thread.sleep0(Native Method)
                  java.base/java.lang.Thread.sleep(Thread.java:509)
                  com.acme.Main.main(Main.java:10)

            #31 "" virtual
                  java.base/java.lang.VirtualThread.parkNanos(VirtualThread.java:631)
                  java.base/java.lang.VirtualThread.sleepNanos(VirtualThread.java:803)
                  java.base/java.lang.Thread.sleep(Thread.java:507)
                  com.acme.Job.run(Job.java:5)
                  java.base/java.lang.VirtualThread.run(VirtualThread.java:309)
            """;
        assertEquals(JcmdThreadDumpParser.Format.TEXT, JcmdThreadDumpParser.detect(text));
        var dump = parser.parse(text, JcmdThreadDumpParser.Format.TEXT);
        assertEquals("21+35-2513", dump.jvmVersion());
        assertEquals(2, dump.threads().size());
        var vt = dump.threads().get(1);
        assertEquals(ThreadKind.VIRTUAL, vt.kind());
        assertEquals(ThreadState.TIMED_WAITING, vt.state());
        assertEquals(5, vt.stackTrace().size());
    }

    @Test
    void jstackIsNotMistakenForJcmd() {
        String jstack = "Full thread dump OpenJDK 64-Bit Server VM (21+35 mixed mode):\n\n" +
            "\"main\" #1 prio=5 os_prio=0 tid=0x1 nid=0x1 runnable\n";
        assertNull(JcmdThreadDumpParser.detect(jstack));
    }

    @Test
    void parsesJvmMajorVersions() {
        assertEquals(21, JvmVersion.majorVersion("Java HotSpot(TM) 64-Bit Server VM (21+35 mixed mode)"));
        assertEquals(17, JvmVersion.majorVersion("OpenJDK 64-Bit Server VM (17.0.9+9-LTS mixed mode, sharing)"));
        assertEquals(8, JvmVersion.majorVersion("Java HotSpot(TM) 64-Bit Server VM (25.202-b08 mixed mode)"));
        assertEquals(25, JvmVersion.majorVersion("OpenJDK 64-Bit Server VM (25+36-3489 mixed mode)"));
        assertEquals(0, JvmVersion.majorVersion(null));
    }
}
