package com.paul.minikv.server;

import com.paul.minikv.command.CommandExecutor;
import com.paul.minikv.storage.StorageEngine;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Owns the listening channel, client connections and event-loop lifecycle. */
public final class MiniKvServer implements AutoCloseable {

    private final String host;
    private final int port;
    private final CommandExecutor executor;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel channel;
    private boolean closed;

    public MiniKvServer(String host, int port, StorageEngine storage) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        this.port = port;
        this.executor = new CommandExecutor(storage);
    }

    public synchronized MiniKvServer start() throws InterruptedException {
        if (closed || boss != null) {
            throw new IllegalStateException("server can only be started once");
        }
        try {
            boss = new NioEventLoopGroup(1);
            workers = new NioEventLoopGroup(2);
            channel = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel client) {
                            client.pipeline().addLast(
                                    new RespFrameDecoder(),
                                    new RespMessageEncoder(),
                                    new CommandHandler(executor)
                            );
                        }
                    })
                    .bind(host, port).sync().channel();
            return this;
        } catch (InterruptedException exception) {
            close();
            throw exception;
        } catch (Exception exception) {
            // Netty's sync() can rethrow a checked bind failure without declaring it.
            close();
            throw new IllegalStateException("Could not start MiniKV on " + host + ":" + port, exception);
        }
    }

    public synchronized int port() {
        if (channel == null) {
            throw new IllegalStateException("server is not started");
        }
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public void awaitClose() throws InterruptedException {
        Channel listening;
        synchronized (this) {
            if (channel == null) {
                throw new IllegalStateException("server is not started");
            }
            listening = channel;
        }
        listening.closeFuture().sync();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (workers != null) {
            workers.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
