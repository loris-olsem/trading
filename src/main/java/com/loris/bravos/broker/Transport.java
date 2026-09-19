package com.loris.bravos.broker;
import java.io.IOException;
import java.util.Map;

@FunctionalInterface
public interface Transport {
    record Response(int status,String body,Map<String,String> headers) {}
    Response request(String method,String path,Map<String,String> headers,String body) throws IOException;
}
