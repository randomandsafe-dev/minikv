package com.paul.minikv.server;

import com.paul.minikv.protocol.RespEncoder;
import com.paul.minikv.protocol.RespValue;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public final class RespMessageEncoder extends MessageToByteEncoder<RespValue> {

    private final RespEncoder encoder = new RespEncoder();

    @Override
    protected void encode(ChannelHandlerContext context, RespValue value, ByteBuf output) {
        output.writeBytes(encoder.encode(value));
    }
}
