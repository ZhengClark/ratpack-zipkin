/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ratpack.zipkin.internal;

import brave.Span;
import brave.Tracer;
import brave.http.HttpClientHandler;
import brave.http.HttpClientParser;
import brave.http.HttpTracing;
import brave.propagation.TraceContext;
import io.netty.buffer.ByteBufAllocator;
import java.net.URI;
import java.time.Duration;
import javax.inject.Inject;
import ratpack.exec.Promise;
import ratpack.exec.Result;
import ratpack.func.Action;
import ratpack.http.MutableHeaders;
import ratpack.http.client.HttpClient;
import ratpack.http.client.ReceivedResponse;
import ratpack.http.client.RequestSpec;
import ratpack.http.client.StreamedResponse;

/**
 * Decorator that adds Zipkin client logging around {@link HttpClient}.
 */
public class ZipkinHttpClientImpl implements HttpClient {

    private final HttpClient delegate;
    private final Tracer tracer;
    private final HttpClientParser parser;
    private final HttpAdapter adapter = new HttpAdapter();
    private final HttpClientHandler<MethodCapturingRequestSpec, Integer> handler;
    private final TraceContext.Injector<MutableHeaders> injector;

    @Inject
    public ZipkinHttpClientImpl(final HttpClient delegate, final HttpTracing httpTracing) {
        this.delegate = delegate;
        this.tracer = httpTracing.tracing().tracer();
        this.parser = httpTracing.clientParser();
        this.handler = HttpClientHandler.create(httpTracing, new HttpAdapter());
        this.injector = httpTracing.tracing().propagation().injector(MutableHeaders::set);
    }

    @Override
    public ByteBufAllocator getByteBufAllocator() {
        return delegate.getByteBufAllocator();
    }

    @Override
    public int getPoolSize() {
        return delegate.getPoolSize();
    }

    @Override
    public Duration getReadTimeout() {
        return delegate.getReadTimeout();
    }

    @Override
    public int getMaxContentLength() {
        return delegate.getMaxContentLength();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public Promise<ReceivedResponse> request(URI uri, Action<? super RequestSpec> action) {
        final Span span = tracer.nextSpan().kind(Span.Kind.CLIENT);
        try(Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
            return delegate
                .request(uri, actionWithSpan(action, span))
                .wiretap(response -> responseWithSpan(response, span));
        } // span.finish() is called after the response is handled.
    }

    @Override
    public Promise<StreamedResponse> requestStream(URI uri, Action<? super RequestSpec> requestConfigurer) {
        final Span span = tracer.nextSpan().kind(Span.Kind.CLIENT);
        try(Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
            return delegate
                .requestStream(uri, actionWithSpan(requestConfigurer, span))
                .wiretap(response -> streamedResponseWithSpan(response, span));
        } // span.finish() is called after the response is handled.
    }

    @Override
    public Promise<ReceivedResponse> get(final URI uri, final Action<? super RequestSpec> requestConfigurer) {
        return request(uri, requestConfigurer.prepend(RequestSpec::get));
    }

    @Override
    public Promise<ReceivedResponse> post(final URI uri, final Action<? super RequestSpec> requestConfigurer) {
        return request(uri, requestConfigurer.prepend(RequestSpec::post));
    }

    private Action<? super RequestSpec> actionWithSpan(final Action<? super RequestSpec> action, final Span span) {
        return action.append(request -> {
            final MethodCapturingRequestSpec captor = new MethodCapturingRequestSpec(request);
            injector.inject(span.context(), captor.getHeaders());
            span.start();
            action.execute(captor);
            // TODO cannot do handler.handleSend() as the span was created out-of-scope
            span.name(parser.spanName(adapter, captor));
            parser.requestTags(adapter, captor, span);
        });
    }

    private void streamedResponseWithSpan(Result<StreamedResponse> response, Span span) {
        handler.handleReceive(response.getValue().getStatusCode(), response.getThrowable(), span);
    }

    private void responseWithSpan(Result<ReceivedResponse> response, Span span) {
        handler.handleReceive(response.getValue().getStatusCode(), response.getThrowable(), span);
    }

    static final class HttpAdapter
        extends brave.http.HttpClientAdapter<MethodCapturingRequestSpec, Integer> {

        @Override public String method(MethodCapturingRequestSpec request) {
            return request.getCapturedMethod().getName();
        }

        @Override public String path(MethodCapturingRequestSpec request) {
            return request.getUri().getPath();
        }

        @Override public String url(MethodCapturingRequestSpec request) {
            return request.getUri().toString();
        }

        @Override public String requestHeader(MethodCapturingRequestSpec request, String name) {
            return request.getHeaders().get(name);
        }

        @Override public Integer statusCode(Integer response) {
            return response;
        }
    }
}
