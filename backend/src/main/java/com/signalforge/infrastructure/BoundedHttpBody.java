package com.signalforge.infrastructure;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bound bytes before buffering an upstream response, including chunked responses. */
public final class BoundedHttpBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public BoundedHttpBody(int limit) { this.limit = limit; }
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
    @Override public void onNext(List<ByteBuffer> buffers) {
        for (var buffer : buffers) {
            if ((long)bytes.size()+buffer.remaining()>limit) {
                subscription.cancel(); result.completeExceptionally(new IllegalStateException("UPSTREAM_RESPONSE_TOO_LARGE")); return;
            }
            byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public void onComplete() { result.complete(bytes.toByteArray()); }
}
