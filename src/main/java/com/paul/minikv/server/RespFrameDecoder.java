package com.paul.minikv.server;

import com.paul.minikv.protocol.RespDecoder;
import com.paul.minikv.protocol.RespIncompleteException;
import com.paul.minikv.protocol.RespProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/** One instance per connection; Netty retains incomplete input between reads. */
public final class RespFrameDecoder extends ByteToMessageDecoder {

    public static final int DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private final RespDecoder decoder = new RespDecoder();
    private final int maxFrameBytes;
    private boolean failed;

    public RespFrameDecoder() {
        this(DEFAULT_MAX_FRAME_BYTES);
    }

    public RespFrameDecoder(int maxFrameBytes) {
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (failed) {
            input.skipBytes(input.readableBytes());
            return;
        }
        byte[] bytes = new byte[Math.min(input.readableBytes(), maxFrameBytes)];
        input.getBytes(input.readerIndex(), bytes);
        try {
            RespDecoder.Decoded decoded = decoder.decodeOne(bytes);
            input.skipBytes(decoded.bytesConsumed());
            output.add(decoded.value());
        } catch (RespIncompleteException exception) {
            if (input.readableBytes() >= maxFrameBytes) {
                failed = true;
                throw new RespProtocolException("RESP frame exceeds maximum size");
            }
        } catch (RespProtocolException exception) {
            failed = true;
            throw exception;
        }
    }

    @Override
    protected void decodeLast(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (input.isReadable()) {
            decode(context, input, output);
            if (!failed && input.isReadable()) {
                failed = true;
                throw new RespProtocolException("connection ended with an incomplete RESP frame");
            }
        }
    }
}
