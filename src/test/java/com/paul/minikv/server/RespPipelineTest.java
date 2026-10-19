package com.paul.minikv.server;

import com.paul.minikv.command.CommandExecutor;
import com.paul.minikv.protocol.*;
import com.paul.minikv.storage.InMemoryStorage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RespPipelineTest {

    private static final String PING = "*1\r\n$4\r\nPING\r\n";

    @Test
    void shouldWaitAtEveryPossibleSplitIncludingBinaryPayload() {
        byte[] wire = new RespEncoder().encode(RespArray.of(java.util.List.of(
                RespBulkString.of("PING".getBytes(StandardCharsets.US_ASCII)),
                RespBulkString.of(new byte[]{0, '\r', '\n', (byte) 0xff}))));
        for (int split = 1; split < wire.length; split++) {
            EmbeddedChannel channel = pipeline(1024);
            try {
                channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(wire, 0, split)));
                assertNull(channel.readOutbound(), "response before frame completion at " + split);
                channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(wire, split, wire.length)));
                ByteBuf reply = channel.readOutbound();
                try {
                    byte[] actual = new byte[reply.readableBytes()];
                    reply.readBytes(actual);
                    assertEquals(RespBulkString.of(new byte[]{0, '\r', '\n', (byte) 0xff}),
                            new RespDecoder().decode(actual));
                } finally {
                    reply.release();
                }
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void shouldHandleByteByByteAndPipelinedFramesBeyondAggregateLimit() {
        EmbeddedChannel channel = pipeline(PING.length());
        try {
            for (byte value : PING.getBytes(StandardCharsets.US_ASCII)) {
                channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{value}));
            }
            assertEquals("+PONG\r\n", response(channel));
            channel.writeInbound(ascii(PING.repeat(20)));
            for (int index = 0; index < 20; index++) {
                assertEquals("+PONG\r\n", response(channel));
            }
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldRetainPartialFrameAfterCompleteFrame() {
        EmbeddedChannel channel = pipeline(1024);
        try {
            channel.writeInbound(ascii(PING + PING.substring(0, 5)));
            assertEquals("+PONG\r\n", response(channel));
            assertNull(channel.readOutbound());
            channel.writeInbound(ascii(PING.substring(5)));
            assertEquals("+PONG\r\n", response(channel));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldKeepConnectionAfterCommandErrors() {
        EmbeddedChannel channel = pipeline(1024);
        try {
            channel.writeInbound(ascii("*1\r\n$3\r\nGET\r\n"
                    + "*1\r\n$4\r\nNOPE\r\n" + "+OK\r\n" + PING));
            for (int index = 0; index < 3; index++) {
                assertTrue(response(channel).startsWith("-ERR "));
            }
            assertEquals("+PONG\r\n", response(channel));
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldCloseOnMalformedOrOversizedFrames() {
        for (String invalid : new String[]{"?bad\r\n", "$1\r\naX", "+bad\n", "*1\r\n".repeat(130),
                "$99999\r\n" + "x".repeat(1024)}) {
            EmbeddedChannel channel = pipeline(1024);
            try {
                channel.writeInbound(ascii(invalid));
                assertTrue(response(channel).startsWith("-ERR "));
                assertFalse(channel.isActive());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    private static EmbeddedChannel pipeline(int maxBytes) {
        return new EmbeddedChannel(new RespFrameDecoder(maxBytes), new RespMessageEncoder(),
                new CommandHandler(new CommandExecutor(new InMemoryStorage())));
    }

    private static ByteBuf ascii(String value) {
        return Unpooled.copiedBuffer(value, StandardCharsets.US_ASCII);
    }

    private static String response(EmbeddedChannel channel) {
        ByteBuf buffer = channel.readOutbound();
        assertNotNull(buffer);
        try {
            return buffer.toString(StandardCharsets.US_ASCII);
        } finally {
            buffer.release();
        }
    }
}
