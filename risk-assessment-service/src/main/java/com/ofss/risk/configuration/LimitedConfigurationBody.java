package com.ofss.risk.configuration;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Counts actual body bytes, including chunked responses. Rejects without consuming error/oversized bodies. */
final class LimitedConfigurationBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;private final long declared;private final boolean acceptable;
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedConfigurationBody(int limit,long declared,boolean acceptable){this.limit=limit;this.declared=declared;this.acceptable=acceptable;}
    @Override public CompletionStage<byte[]> getBody(){return result;}
    @Override public void onSubscribe(Flow.Subscription subscription){
        if(this.subscription!=null){subscription.cancel();return;}this.subscription=subscription;
        if(!acceptable || declared>limit){reject();return;}subscription.request(1);
    }
    @Override public void onNext(List<ByteBuffer> buffers){
        if(result.isDone()){if(subscription!=null)subscription.cancel();return;}
        for(var buffer:buffers){if(buffer.remaining()>limit-bytes.size()){reject();return;}
            byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}
        subscription.request(1);
    }
    @Override public void onError(Throwable ignored){reject();}
    @Override public void onComplete(){if(declared>=0 && declared!=bytes.size()){reject();return;}result.complete(bytes.toByteArray());}
    private void reject(){if(subscription!=null)subscription.cancel();result.completeExceptionally(new IllegalStateException("Configuration HTTP response rejected"));}
}
