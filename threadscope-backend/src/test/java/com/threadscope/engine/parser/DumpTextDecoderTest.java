package com.threadscope.engine.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DumpTextDecoderTest {

    private static final String TEXT = "\"订单-同步-1\" #42 prio=5 os_prio=0 tid=0x1 nid=0x2 runnable\n"
        + "   java.lang.Thread.State: RUNNABLE\n".repeat(10);

    @Test
    void utf8IsDecodedAsIs() {
        var d = DumpTextDecoder.decode(TEXT.getBytes(StandardCharsets.UTF_8), null);
        assertEquals("UTF-8", d.charset());
        assertFalse(d.fallback());
        assertEquals(TEXT, d.text());
    }

    @Test
    void gbkFallsBackToGb18030() {
        var d = DumpTextDecoder.decode(TEXT.getBytes(Charset.forName("GBK")), "auto");
        assertEquals("GB18030", d.charset());
        assertTrue(d.fallback());
        assertEquals(TEXT, d.text());
    }

    @Test
    void utf16WithBomIsDetected() {
        byte[] body = TEXT.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[body.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(body, 0, withBom, 2, body.length);
        var d = DumpTextDecoder.decode(withBom, null);
        assertEquals("UTF-16LE", d.charset());
        assertEquals(TEXT, d.text());
    }

    @Test
    void utf16WithoutBomIsSniffed() {
        var d = DumpTextDecoder.decode(TEXT.getBytes(StandardCharsets.UTF_16LE), null);
        assertEquals("UTF-16LE", d.charset());
        assertEquals(TEXT, d.text());
    }

    @Test
    void explicitCharsetWins() {
        var d = DumpTextDecoder.decode(TEXT.getBytes(Charset.forName("GBK")), "GBK");
        assertEquals(TEXT, d.text());
    }
}
