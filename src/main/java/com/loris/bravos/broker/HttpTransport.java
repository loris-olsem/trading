package com.loris.bravos.broker;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** One origin only, no automatic redirects, bounded response, no request logging. */
public final class HttpTransport implements Transport {
    private final URI origin;
    private final HttpClient client;
    public HttpTransport(URI origin,boolean cookies) {
        String host=origin.getHost();
        boolean official="https".equals(origin.getScheme()) && Set.of("public-api.etoro.com","bravosresearch.com").contains(host) && origin.getPort()==-1;
        boolean loopback="http".equals(origin.getScheme()) && "127.0.0.1".equals(host);
        if((!official && !loopback) || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null || !List.of("","/").contains(origin.getPath())) throw new IllegalArgumentException("UNAPPROVED_ORIGIN");
        this.origin=origin;
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER);
        if(cookies) builder.cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER));
        client=builder.build();
    }
    @Override public Response request(String method,String path,Map<String,String> headers,String body) throws IOException {
        if(!path.startsWith("/") || path.startsWith("//") || path.contains("\\")) throw new IOException("INVALID_REQUEST_PATH");
        URI uri=origin.resolve(path);
        if(!Objects.equals(uri.getHost(),origin.getHost()) || uri.getPort()!=origin.getPort() || !uri.getScheme().equals(origin.getScheme()) || uri.getUserInfo()!=null) throw new IOException("ORIGIN_ESCAPE");
        try {
            var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
            headers.forEach(builder::header);
            builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),4_000_000));
            byte[] bytes=response.body();
            Map<String,String> responseHeaders=new HashMap<>();
            for(String name:List.of("location","retry-after","content-type")) response.headers().firstValue(name).ifPresent(v->responseHeaders.put(name,v));
            return new Response(response.statusCode(),new String(bytes,StandardCharsets.UTF_8),Map.copyOf(responseHeaders));
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("REQUEST_INTERRUPTED"); }
        catch(IllegalArgumentException e) { throw new IOException("INVALID_HTTP_REQUEST"); }
    }
}
