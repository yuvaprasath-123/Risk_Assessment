package com.ofss.risk.configuration;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.mockito.Mockito.*;

/** In-memory JDK HTTP boundary; exercises the real request and streamed BodyHandler without opening a service/socket. */
final class ConfigurationHttpHarness {
    final HttpClient client=mock(HttpClient.class);
    final List<HttpRequest> requests=new ArrayList<>();
    int status=200;Map<String,List<String>> headers=new HashMap<>(Map.of("Content-Type",List.of("application/json")));
    byte[] body=ConfigurationHttpFixtures.response(true);int chunkSize=257;boolean stalled, cancelled;
    CompletableFuture<HttpResponse<byte[]>> last;
    java.util.function.Function<HttpRequest,Integer> authorize;
    @SuppressWarnings({"unchecked","rawtypes"}) ConfigurationHttpHarness(){
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{
            HttpRequest request=inv.getArgument(0);requests.add(request);HttpResponse.BodyHandler<byte[]> handler=inv.getArgument(1);
            if(authorize!=null)status=authorize.apply(request);
            var info=mock(HttpResponse.ResponseInfo.class);when(info.statusCode()).thenReturn(status);when(info.headers()).thenReturn(HttpHeaders.of(headers,(a,b)->true));
            var sub=handler.apply(info);cancelled=false;sub.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled=true;}});
            for(int pos=0;pos<body.length&&!cancelled;pos+=chunkSize)sub.onNext(List.of(ByteBuffer.wrap(body,pos,Math.min(chunkSize,body.length-pos))));
            if(!stalled&&!cancelled)sub.onComplete();
            last=sub.getBody().toCompletableFuture().thenApply(bytes->{HttpResponse<byte[]> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(bytes);return response;});
            return last;
        });
    }
}
