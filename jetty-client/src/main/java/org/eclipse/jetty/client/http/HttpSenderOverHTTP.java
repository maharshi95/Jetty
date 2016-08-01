//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequestException;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;

public class HttpSenderOverHTTP extends HttpSender
{
    private final HttpGenerator generator = new HttpGenerator();

    public HttpSenderOverHTTP(HttpChannelOverHTTP channel)
    {
        super(channel);
    }

    @Override
    public HttpChannelOverHTTP getHttpChannel()
    {
        return (HttpChannelOverHTTP)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, HttpContent content, Callback callback)
    {
        try
        {
            new HeadersCallback(exchange, content, callback).iterate();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            callback.failed(x);
        }
    }

    @Override
    protected void sendContent(HttpExchange exchange, HttpContent content, Callback callback)
    {
        try
        {
            HttpClient client = getHttpChannel().getHttpDestination().getHttpClient();
            ByteBufferPool bufferPool = client.getByteBufferPool();
            ByteBuffer chunk = null;
            while (true)
            {
                ByteBuffer contentBuffer = content.getByteBuffer();
                boolean lastContent = content.isLast();
                HttpGenerator.Result result = generator.generateRequest(null, null, chunk, contentBuffer, lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated content ({} bytes) - {}/{}",
                            contentBuffer == null ? -1 : contentBuffer.remaining(),
                            result, generator);
                switch (result)
                {
                    case NEED_CHUNK:
                    {
                        chunk = bufferPool.acquire(HttpGenerator.CHUNK_SIZE, false);
                        break;
                    }
                    case FLUSH:
                    {
                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        if (chunk != null)
                            endPoint.write(new ByteBufferRecyclerCallback(callback, bufferPool, chunk), chunk, contentBuffer);
                        else
                            endPoint.write(callback, contentBuffer);
                        return;
                    }
                    case SHUTDOWN_OUT:
                    {
                        shutdownOutput();
                        break;
                    }
                    case CONTINUE:
                    {
                        if (lastContent)
                            break;
                        callback.succeeded();
                        return;
                    }
                    case DONE:
                    {
                        callback.succeeded();
                        return;
                    }
                    default:
                    {
                        throw new IllegalStateException(result.toString());
                    }
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            callback.failed(x);
        }
    }

    @Override
    protected void reset()
    {
        generator.reset();
        super.reset();
    }

    @Override
    protected void dispose()
    {
        generator.abort();
        super.dispose();
        shutdownOutput();
    }

    private void shutdownOutput()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Request shutdown output {}", getHttpExchange().getRequest());
        getHttpChannel().getHttpConnection().getEndPoint().shutdownOutput();
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", super.toString(), generator);
    }

    private class HeadersCallback extends IteratingCallback
    {
        private final HttpExchange exchange;
        private final Callback callback;
        private final MetaData.Request metaData;
        private ByteBuffer headerBuffer;
        private ByteBuffer chunkBuffer;
        private ByteBuffer contentBuffer;
        private boolean lastContent;
        private boolean generated;

        public HeadersCallback(HttpExchange exchange, HttpContent content, Callback callback)
        {
            super(false);
            this.exchange = exchange;
            this.callback = callback;

            Request request = exchange.getRequest();
            ContentProvider requestContent = request.getContent();
            long contentLength = requestContent == null ? -1 : requestContent.getLength();
            String path = request.getPath();
            String query = request.getQuery();
            if (query != null)
                path += "?" + query;
            metaData = new MetaData.Request(request.getMethod(), new HttpURI(path), request.getVersion(), request.getHeaders(), contentLength);

            if (!expects100Continue(request))
            {
                content.advance();
                contentBuffer = content.getByteBuffer();
                lastContent = content.isLast();
            }
        }

        @Override
        protected Action process() throws Exception
        {
            HttpClient client = getHttpChannel().getHttpDestination().getHttpClient();
            ByteBufferPool bufferPool = client.getByteBufferPool();

            while (true)
            {
                HttpGenerator.Result result = generator.generateRequest(metaData, headerBuffer, chunkBuffer, contentBuffer, lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated headers ({} bytes), chunk ({} bytes), content ({} bytes) - {}/{}",
                            headerBuffer == null ? -1 : headerBuffer.remaining(),
                            chunkBuffer == null ? -1 : chunkBuffer.remaining(),
                            contentBuffer == null ? -1 : contentBuffer.remaining(),
                            result, generator);
                switch (result)
                {
                    case NEED_HEADER:
                    {
                        headerBuffer = bufferPool.acquire(client.getRequestBufferSize(), false);
                        break;
                    }
                    case NEED_CHUNK:
                    {
                        chunkBuffer = bufferPool.acquire(HttpGenerator.CHUNK_SIZE, false);
                        break;
                    }
                    case FLUSH:
                    {
                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        if (chunkBuffer == null)
                        {
                            if (contentBuffer == null)
                                endPoint.write(this, headerBuffer);
                            else
                                endPoint.write(this, headerBuffer, contentBuffer);
                        }
                        else
                        {
                            if (contentBuffer == null)
                                endPoint.write(this, headerBuffer, chunkBuffer);
                            else
                                endPoint.write(this, headerBuffer, chunkBuffer, contentBuffer);
                        }
                        generated = true;
                        return Action.SCHEDULED;
                    }
                    case SHUTDOWN_OUT:
                    {
                        shutdownOutput();
                        return Action.SUCCEEDED;
                    }
                    case CONTINUE:
                    {
                        if (generated)
                            return Action.SUCCEEDED;
                        break;
                    }
                    case DONE:
                    {
                        if (generated)
                            return Action.SUCCEEDED;
                        // The headers have already been generated by some
                        // other thread, perhaps by a concurrent abort().
                        throw new HttpRequestException("Could not generate headers", exchange.getRequest());
                    }
                    default:
                    {
                        throw new IllegalStateException(result.toString());
                    }
                }
            }
        }

        @Override
        public void succeeded()
        {
            release();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            release();
            callback.failed(x);
            super.failed(x);
        }

        @Override
        protected void onCompleteSuccess()
        {
            super.onCompleteSuccess();
            callback.succeeded();
        }

        private void release()
        {
            HttpClient client = getHttpChannel().getHttpDestination().getHttpClient();
            ByteBufferPool bufferPool = client.getByteBufferPool();
            bufferPool.release(headerBuffer);
            headerBuffer = null;
            if (chunkBuffer != null)
                bufferPool.release(chunkBuffer);
            chunkBuffer = null;
        }
    }

    private class ByteBufferRecyclerCallback extends Callback.Nested
    {
        private final ByteBufferPool pool;
        private final ByteBuffer[] buffers;

        private ByteBufferRecyclerCallback(Callback callback, ByteBufferPool pool, ByteBuffer... buffers)
        {
            super(callback);
            this.pool = pool;
            this.buffers = buffers;
        }

        @Override
        public void succeeded()
        {
            for (ByteBuffer buffer : buffers)
            {
                assert !buffer.hasRemaining();
                pool.release(buffer);
            }
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            for (ByteBuffer buffer : buffers)
                pool.release(buffer);
            super.failed(x);
        }
    }
}
