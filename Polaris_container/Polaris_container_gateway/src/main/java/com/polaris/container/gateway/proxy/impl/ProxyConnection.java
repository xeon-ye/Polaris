package com.polaris.container.gateway.proxy.impl;

import static com.polaris.container.gateway.proxy.impl.ConnectionState.AWAITING_CHUNK;
import static com.polaris.container.gateway.proxy.impl.ConnectionState.AWAITING_INITIAL;
import static com.polaris.container.gateway.proxy.impl.ConnectionState.DISCONNECTED;

import javax.net.ssl.SSLEngine;

import com.polaris.container.gateway.pojo.HttpRequestWrapper;
import com.polaris.container.gateway.proxy.HttpFilters;
import com.polaris.container.gateway.util.ProxyUtils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

/**
 * <p>
 * Base class for objects that represent a connection to/from our proxy.
 * </p>
 * <p>
 * A ProxyConnection models a bidirectional message flow on top of a Netty
 * {@link Channel}.
 * </p>
 * <p>
 * The {@link #read(Object)} method is called whenever a new message arrives on
 * the underlying socket.
 * </p>
 * <p>
 * The {@link #write(Object)} method can be called by anyone wanting to write
 * data out of the connection.
 * </p>
 * <p>
 * ProxyConnection has a lifecycle and its current state within that lifecycle
 * is recorded as a {@link ConnectionState}. The allowed states and transitions
 * vary a little depending on the concrete implementation of ProxyConnection.
 * However, all ProxyConnections share the following lifecycle events:
 * </p>
 * 
 * <ul>
 * <li>{@link #connected()} - Once the underlying channel is active, the
 * ProxyConnection is considered connected and moves into
 * {@link ConnectionState#AWAITING_INITIAL}. The Channel is recorded at this
 * time for later referencing.</li>
 * <li>{@link #disconnected()} - When the underlying channel goes inactive, the
 * ProxyConnection moves into {@link ConnectionState#DISCONNECTED}</li>
 * <li>{@link #becameWritable()} - When the underlying channel becomes
 * writeable, this callback is invoked.</li>
 * </ul>
 * 
 * <p>
 * By default, incoming data on the underlying channel is automatically read and
 * passed to the {@link #read(Object)} method. Reading can be stopped and
 * resumed using {@link #stopReading()} and {@link #resumeReading()}.
 * </p>
 * 
 * @param <I>
 *            the type of "initial" message. This will be either
 *            {@link HttpResponse} or {@link HttpRequest}.
 */
@SuppressWarnings({ "unchecked" })
public abstract class ProxyConnection<I extends HttpObject> extends
        SimpleChannelInboundHandler<Object> {
    protected final ProxyConnectionLogger LOG = new ProxyConnectionLogger(this);

    protected final DefaultHttpProxyServer proxyServer;

    protected volatile ChannelHandlerContext ctx;
    protected volatile Channel channel;

    private volatile ConnectionState currentState;
    protected volatile long lastReadTime = 0;

    /**
     * If using encryption, this holds our {@link SSLEngine}.
     */
    protected volatile SSLEngine sslEngine;

    /**
     * Construct a new ProxyConnection.
     * 
     * @param initialState
     *            the state in which this connection starts out
     * @param proxyServer
     *            the {@link DefaultHttpProxyServer} in which we're running
     * @param runsAsSslClient
     *            determines whether this connection acts as an SSL client or
     *            server (determines who does the handshake)
     */
    protected ProxyConnection(ConnectionState initialState,
            DefaultHttpProxyServer proxyServer) {
        become(initialState);
        this.proxyServer = proxyServer;
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    /**
     * Read is invoked automatically by Netty as messages arrive on the socket.
     * 
     * @param msg
     */
    protected void read(Object msg) {
        LOG.debug("Reading: {}", msg);

        lastReadTime = System.currentTimeMillis();

        readHTTP((HttpObject) msg);
    }

    /**
     * Handles reading {@link HttpObject}s.
     * 
     * @param httpObject
     */
    private void readHTTP(HttpObject httpObject) {
        ConnectionState nextState = getCurrentState();
        switch (getCurrentState()) {
        case AWAITING_INITIAL:
            if (httpObject instanceof HttpMessage) {
                nextState = readHTTPInitial((I) httpObject);
            } else {
                // Similar to the AWAITING_PROXY_AUTHENTICATION case below, we may enter an AWAITING_INITIAL
                // state if the proxy responded to an earlier request with a 502 or 504 response, or a short-circuit
                // response from a filter. The client may have sent some chunked HttpContent associated with the request
                // after the short-circuit response was sent. We can safely drop them.
                LOG.debug("Dropping message because HTTP object was not an HttpMessage. HTTP object may be orphaned content from a short-circuited response. Message: {}", httpObject);
            }
            break;
        case AWAITING_CHUNK:
            HttpContent chunk = (HttpContent) httpObject;
            readHTTPChunk(chunk);
            nextState = ProxyUtils.isLastChunk(chunk) ? AWAITING_INITIAL
                    : AWAITING_CHUNK;
            break;
        case AWAITING_PROXY_AUTHENTICATION:
            if (httpObject instanceof HttpRequest) {
                // Once we get an HttpRequest, try to process it as usual
                nextState = readHTTPInitial((I) httpObject);
            } else {
                // Anything that's not an HttpRequest that came in while
                // we're pending authentication gets dropped on the floor. This
                // can happen if the connected host already sent us some chunks
                // (e.g. from a POST) after an initial request that turned out
                // to require authentication.
            }
            break;
        case CONNECTING:
            LOG.warn("Attempted to read from connection that's in the process of connecting.  This shouldn't happen.");
            break;
        case HANDSHAKING:
            LOG.warn(
                    "Attempted to read from connection that's in the process of handshaking.  This shouldn't happen.",
                    channel);
            break;
        case DISCONNECT_REQUESTED:
        case DISCONNECTED:
            LOG.info("Ignoring message since the connection is closed or about to close");
            break;
        }
        become(nextState);
    }

    /**
     * Implement this to handle reading the initial object (e.g.
     * {@link HttpRequest} or {@link HttpResponse}).
     * 
     * @param httpObject
     * @return
     */
    protected abstract ConnectionState readHTTPInitial(I httpObject);

    /**
     * Implement this to handle reading a chunk in a chunked transfer.
     * 
     * @param chunk
     */
    protected abstract void readHTTPChunk(HttpContent chunk);

    /***************************************************************************
     * Writing
     **************************************************************************/

    /**
     * This method is called by users of the ProxyConnection to send stuff out
     * over the socket.
     * 
     * @param msg
     */
    void write(Object msg) {
        if (msg instanceof HttpRequestWrapper) {
            if (((HttpRequestWrapper)msg).getOrgHttpRequest() instanceof ReferenceCounted) {
                LOG.debug("Retaining reference counted message");
                ((ReferenceCounted) ((HttpRequestWrapper)msg).getOrgHttpRequest()).retain();
            }
        } else {
            if (msg instanceof ReferenceCounted) {
                LOG.debug("Retaining reference counted message");
                ((ReferenceCounted) msg).retain();
            }
        }

        doWrite(msg);
    }

    void doWrite(Object msg) {
        LOG.debug("Writing: {}", msg);

        try {
            if (msg instanceof HttpObject) {
                writeHttp((HttpObject) msg);
            } else {
                writeRaw((ByteBuf) msg);
            }
        } finally {
            LOG.debug("Wrote: {}", msg);
        }
    }

    /**
     * Writes HttpObjects to the connection asynchronously.
     * 
     * @param httpObject
     */
    protected void writeHttp(HttpObject httpObject) {
        if (ProxyUtils.isLastChunk(httpObject)) {
            writeToChannelNoFlush(httpObject);
            LOG.debug("Writing an empty buffer to signal the end of our chunked transfer");
            writeToChannel(Unpooled.EMPTY_BUFFER);
        } else {
            writeToChannel(httpObject);
        }
    }

    /**
     * Writes raw buffers to the connection.
     * 
     * @param buf
     */
    protected void writeRaw(ByteBuf buf) {
        writeToChannel(buf);
    }

    protected ChannelFuture writeToChannel(final Object msg) {
        return channel.writeAndFlush(msg);
    }
    protected ChannelFuture writeToChannelNoFlush(final Object msg) {
        if (msg instanceof HttpRequestWrapper) {
            return channel.write(((HttpRequestWrapper)msg).getOrgHttpRequest());
        } else {
            return channel.write(msg);
        }
    }

    /***************************************************************************
     * Lifecycle
     **************************************************************************/

    /**
     * This method is called as soon as the underlying {@link Channel} is
     * connected. Note that for proxies with complex {@link ConnectionFlow}s
     * that include SSL handshaking and other such things, just because the
     * {@link Channel} is connected doesn't mean that our connection is fully
     * established.
     */
    protected void connected() {
        LOG.debug("Connected");
    }

    /**
     * This method is called as soon as the underlying {@link Channel} becomes
     * disconnected.
     */
    protected void disconnected() {
        become(DISCONNECTED);
        LOG.debug("Disconnected");
    }

    /**
     * This method is called when the underlying {@link Channel} times out due
     * to an idle timeout.
     */
    protected void timedOut() {
        disconnect();
    }

    /**
     * Encrypts traffic on this connection with SSL/TLS.
     * 
     * @param sslEngine
     *            the {@link SSLEngine} for doing the encryption
     * @param authenticateClients
     *            determines whether to authenticate clients or not
     * @return a Future for when the SSL handshake has completed
     */
    protected Future<Channel> encrypt(SSLEngine sslEngine) {
        return encrypt(ctx.pipeline(), sslEngine);
    }

    /**
     * Encrypts traffic on this connection with SSL/TLS.
     * 
     * @param pipeline
     *            the ChannelPipeline on which to enable encryption
     * @param sslEngine
     *            the {@link SSLEngine} for doing the encryption
     * @param authenticateClients
     *            determines whether to authenticate clients or not
     * @return a Future for when the SSL handshake has completed
     */
    protected Future<Channel> encrypt(ChannelPipeline pipeline,
            SSLEngine sslEngine) {
        LOG.debug("Enabling encryption with SSLEngine: {}",
                sslEngine);
        this.sslEngine = sslEngine;
        pipeline.channel().config().setAutoRead(true);
        SslHandler handler = new SslHandler(sslEngine);
        if(pipeline.get("ssl") == null) {
            pipeline.addFirst("ssl", handler);
        } else {
            // The second SSL handler is added to handle the case
            // where the proxy (running as MITM) has to chain with
            // another SSL enabled proxy. The second SSL handler
            // is to perform SSL with the server.
            pipeline.addAfter("ssl", "sslWithServer", handler);
        }
        return handler.handshakeFuture();
    }

    /**
     * Callback that's invoked if this connection becomes saturated.
     */
    protected void becameSaturated() {
        LOG.debug("Became saturated");
    }

    /**
     * Callback that's invoked when this connection becomes writeable again.
     */
    protected void becameWritable() {
        LOG.debug("Became writeable");
    }

    /**
     * Override this to handle exceptions that occurred during asynchronous
     * processing on the {@link Channel}.
     * 
     * @param cause
     */
    protected void exceptionCaught(Throwable cause) {
    }

    /***************************************************************************
     * State/Management
     **************************************************************************/
    /**
     * Disconnects. This will wait for pending writes to be flushed before
     * disconnecting.
     * 
     * @return Future<Void> for when we're done disconnecting. If we weren't
     *         connected, this returns null.
     */
    Future<Void> disconnect() {
        if (channel == null) {
            return null;
        } else {
            final Promise<Void> promise = channel.newPromise();
            writeToChannel(Unpooled.EMPTY_BUFFER).addListener(
                    new GenericFutureListener<Future<? super Void>>() {
                        @Override
                        public void operationComplete(
                                Future<? super Void> future)
                                throws Exception {
                            closeChannel(promise);
                        }
                    });
            return promise;
        }
    }

    private void closeChannel(final Promise<Void> promise) {
        channel.close().addListener(
                new GenericFutureListener<Future<? super Void>>() {
                    public void operationComplete(
                            Future<? super Void> future)
                            throws Exception {
                        if (future
                                .isSuccess()) {
                            promise.setSuccess(null);
                        } else {
                            promise.setFailure(future
                                    .cause());
                        }
                    };
                });
    }

    /**
     * Indicates whether or not this connection is saturated (i.e. not
     * writeable).
     * 
     * @return
     */
    protected boolean isSaturated() {
        return !this.channel.isWritable();
    }

    /**
     * Utility for checking current state.
     * 
     * @param state
     * @return
     */
    protected boolean is(ConnectionState state) {
        return currentState == state;
    }

    /**
     * If this connection is currently in the process of going through a
     * {@link ConnectionFlow}, this will return true.
     * 
     * @return
     */
    protected boolean isConnecting() {
        return currentState.isPartOfConnectionFlow();
    }

    /**
     * Udpates the current state to the given value.
     * 
     * @param state
     */
    protected void become(ConnectionState state) {
        this.currentState = state;
    }

    protected ConnectionState getCurrentState() {
        return currentState;
    }

    public SSLEngine getSslEngine() {
        return sslEngine;
    }

    /**
     * Call this to stop reading.
     */
    protected void stopReading() {
        LOG.debug("Stopped reading");
        this.channel.config().setAutoRead(false);
    }

    /**
     * Call this to resume reading.
     */
    protected void resumeReading() {
        LOG.debug("Resumed reading");
        this.channel.config().setAutoRead(true);
    }

    /**
     * Request the ProxyServer for Filters.
     * 
     * By default, no-op filters are returned by DefaultHttpProxyServer.
     * Subclasses of ProxyConnection can change this behaviour.
     * 
     * @param httpRequest
     *            Filter attached to the give HttpRequest (if any)
     * @return
     */
    protected HttpFilters getHttpFiltersFromProxyServer(HttpRequest httpRequest) {
        return proxyServer.getFiltersSource().filterRequest(httpRequest, ctx);
    }

    ProxyConnectionLogger getLOG() {
        return LOG;
    }

    /***************************************************************************
     * Adapting the Netty API
     **************************************************************************/
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        read(msg);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        try {
            channelRegistered0(ctx);
        } finally {
            super.channelRegistered(ctx);
        }
    }
    
    protected void channelRegistered0(ChannelHandlerContext ctx) throws Exception {
        if (channel != null) {
            return;
        }
        this.ctx = ctx;
        this.channel = ctx.channel();
        this.proxyServer.registerChannel(ctx.channel());
    }

    /**
     * Only once the Netty Channel is active to we recognize the ProxyConnection
     * as connected.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        try {
            connected();
        } finally {
            super.channelActive(ctx);
        }
    }

    /**
     * As soon as the Netty Channel is inactive, we recognize the
     * ProxyConnection as disconnected.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            disconnected();
        } finally {
            super.channelInactive(ctx);
        }
    }

    @Override
    public final void channelWritabilityChanged(ChannelHandlerContext ctx)
            throws Exception {
        LOG.debug("Writability changed. Is writable: {}", channel.isWritable());
        try {
            if (this.channel.isWritable()) {
                becameWritable();
            } else {
                becameSaturated();
            }
        } finally {
            super.channelWritabilityChanged(ctx);
        }
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        exceptionCaught(cause);
    }

    /**
     * <p>
     * We're looking for {@link IdleStateEvent}s to see if we need to
     * disconnect.
     * </p>
     * 
     * <p>
     * Note - we don't care what kind of IdleState we got. Thanks to <a
     * href="https://github.com/qbast">qbast</a> for pointing this out.
     * </p>
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
            throws Exception {
        try {
            if (evt instanceof IdleStateEvent) {
                LOG.debug("Got idle");
                timedOut();
            }
        } finally {
            super.userEventTriggered(ctx, evt);
        }
    }

    /***************************************************************************
     * Activity Tracking/Statistics
     **************************************************************************/

    /**
     * Utility handler for monitoring requests read on this connection.
     */
    @Sharable
    protected abstract class RequestReadMonitor extends
            ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception {
            try {
                if (msg instanceof HttpRequest) {
                    requestRead((HttpRequest) msg);
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }

        protected abstract void requestRead(HttpRequest httpRequest);
    }

    /**
     * Utility handler for monitoring responses read on this connection.
     */
    @Sharable
    protected abstract class ResponseReadMonitor extends
            ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception {
            try {
                if (msg instanceof HttpResponse) {
                    responseRead((HttpResponse) msg);
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }

        protected abstract void responseRead(HttpResponse httpResponse);
    }

    /**
     * Utility handler for monitoring requests written on this connection.
     */
    @Sharable
    protected abstract class RequestWrittenMonitor extends
            ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx,
                Object msg, ChannelPromise promise)
                throws Exception {
            HttpRequest originalRequest = null;
            if (msg instanceof HttpRequest) {
                originalRequest = (HttpRequest) msg;
            }

            if (null != originalRequest) {
                requestWriting(originalRequest);
            }

            super.write(ctx, msg, promise);

            if (null != originalRequest) {
                requestWritten(originalRequest);
            }

            if (msg instanceof HttpContent) {
                contentWritten((HttpContent) msg);
            }
        }

        /**
         * Invoked immediately before an HttpRequest is written.
         */
        protected abstract void requestWriting(HttpRequest httpRequest);

        /**
         * Invoked immediately after an HttpRequest has been sent.
         */
        protected abstract void requestWritten(HttpRequest httpRequest);

        /**
         * Invoked immediately after an HttpContent has been sent.
         */
        protected abstract void contentWritten(HttpContent httpContent);
    }

    /**
     * Utility handler for monitoring responses written on this connection.
     */
    @Sharable
    protected abstract class ResponseWrittenMonitor extends
            ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx,
                Object msg, ChannelPromise promise)
                throws Exception {
            try {
                if (msg instanceof HttpResponse) {
                    responseWritten(((HttpResponse) msg));
                }
            } catch (Throwable t) {
                LOG.warn("Error while invoking responseWritten callback", t);
            } finally {
                super.write(ctx, msg, promise);
            }
        }

        protected abstract void responseWritten(HttpResponse httpResponse);
    }
    
    /**
     *
     * @return
     */
    public Channel getChannel() {
        return channel;
    }

}
