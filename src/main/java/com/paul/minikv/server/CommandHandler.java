package com.paul.minikv.server;

import com.paul.minikv.command.CommandExecutor;
import com.paul.minikv.command.RespCommandParser;
import com.paul.minikv.protocol.RespError;
import com.paul.minikv.protocol.RespProtocolException;
import com.paul.minikv.protocol.RespValue;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public final class CommandHandler extends SimpleChannelInboundHandler<RespValue> {

    private final RespCommandParser parser = new RespCommandParser();
    private final CommandExecutor executor;

    public CommandHandler(CommandExecutor executor) {
        this.executor = executor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, RespValue value) {
        RespValue response;
        try {
            response = executor.execute(parser.parse(value));
        } catch (RespProtocolException exception) {
            // The frame is intact, so the next command can still be read safely.
            response = new RespError("ERR invalid command format");
        }
        context.writeAndFlush(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        if (context.channel().isActive()) {
            context.writeAndFlush(new RespError("ERR protocol or connection failure"))
                    .addListener(ChannelFutureListener.CLOSE);
        } else {
            context.close();
        }
    }
}
