package com.loris.bravos.broker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpTransportTest {
    @Test void actualHttpPreservesPayloadAndNeverFollowsRedirects() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/echo",e->{ var bytes=e.getRequestBody().readAllBytes(); e.sendResponseHeaders(200,bytes.length); e.getResponseBody().write(bytes); e.close(); });
        server.createContext("/redirect",e->{ e.getResponseHeaders().set("Location","https://evil.example/"); e.sendResponseHeaders(302,-1); e.close(); });
        server.createContext("/huge",e->{ var bytes=new byte[4_000_001]; e.sendResponseHeaders(200,bytes.length); try {e.getResponseBody().write(bytes);} finally {e.close();} });
        server.start();
        try {
            var transport=new HttpTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),true);
            assertEquals("payload",transport.request("POST","/echo",Map.of("Content-Type","application/json"),"payload").body());
            var redirect=transport.request("GET","/redirect",Map.of(),null); assertEquals(302,redirect.status()); assertEquals("https://evil.example/",redirect.headers().get("location"));
            assertThrows(IOException.class,()->transport.request("GET","//evil.example/x",Map.of(),null));
            assertThrows(IOException.class,()->transport.request("GET","\\evil",Map.of(),null));
            assertThrows(IOException.class,()->transport.request("GET","/huge",Map.of(),null));
        } finally { server.stop(0); }
        for(String bad:List.of("http://public-api.etoro.com","https://evil.example","https://public-api.etoro.com:444","https://u@public-api.etoro.com","https://public-api.etoro.com/?q=1")) assertThrows(IllegalArgumentException.class,()->new HttpTransport(URI.create(bad),false));
    }
}
