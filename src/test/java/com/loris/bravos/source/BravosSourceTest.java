package com.loris.bravos.source;

import com.loris.bravos.broker.Transport;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BravosSourceTest {
    static final String ORIGIN="https://bravosresearch.com";
    static String card(String url,String date) { return "<div class=right><a href='"+url+"'>Initiating a long position in CF</a> "+date+"</div>"; }
    static String article(String price) { return "<article id=post-12><h1>Initiating Long on CF ($CF)</h1><div class=entry-content>Trade Alert 09/01/2026<br>Save post<br>already read<br><p>We are initiating CF at $"+price+" with a weight of 5.</p><p>Entry: $"+price+"<br>Weight Allocation: 5<br>Suggested Stop Loss (SL): $90</p><div id=comments>A comment at $999</div></div></article>"; }
    static String dashboard() { return "<div class=dash-newsletter><p class=h4>Tactical Portfolio <span>date</span></p><div class=asset-ratings><div class=tbody><div class=tr><div class=td>Company ($CF)</div><div class='td action'>Long</div><div class='td rating'>5</div></div></div></div></div>"; }
    static Transport.Response ok(String body) { return new Transport.Response(200,body,Map.of()); }
    @Test void authenticatesOnlyToIntendedHostAndConfirmsLogin() throws Exception {
        List<String> calls=new ArrayList<>(); boolean[] logged={false};
        var source=new BravosSource((m,p,h,b)-> {
            calls.add(m+" "+p);
            if(m.equals("POST")) { assertEquals("application/x-www-form-urlencoded",h.get("Content-Type")); assertTrue(b.contains("username=user%2Btest")); assertTrue(b.contains("password=secret%26")); logged[0]=true; return new Transport.Response(302,"",Map.of("location","/my-account/")); }
            return ok(logged[0]?"<a href='/customer-logout'>Log out</a>":"<form><input name=username><input type=password name=password><input type=hidden name=nonce value=abc><button name=login value=go></button></form>");
        });
        source.login("user+test","secret&"); assertEquals(1,calls.stream().filter(s->s.startsWith("POST")).count());
        source.login("user+test","secret&"); assertEquals(1,calls.stream().filter(s->s.startsWith("POST")).count());
        assertThrows(IOException.class,()->new BravosSource((m,p,h,b)->ok("login missing")).login("u","p"));
        assertThrows(IllegalArgumentException.class,()->new BravosSource((m,p,h,b)->ok("<form action='https://evil.example/'><input name=username><input type=password name=password></form>")).login("u","p"));
    }
    @Test void completeGapAndOlderPageBoundaryWithAuthoredOnlyBody() throws Exception {
        List<String> pages=new ArrayList<>();
        var source=new BravosSource((m,p,h,b)-> { pages.add(p); return ok(switch(p) {
            case "/category/portfolio-update/" -> card("/news-feed/cf/","09/01/2026");
            case "/category/portfolio-update/page/2/" -> card("/news-feed/old/","01/01/2026");
            case "/research/" -> dashboard();
            default -> article("100");
        }); });
        var scan=source.scan(LocalDate.of(2026,2,1),List.of(ORIGIN+"/news-feed/cf/"));
        assertTrue(scan.complete(),scan.errors().toString()); assertEquals(2,scan.pages().size()); assertEquals(1,scan.alerts().size());
        assertEquals("bravos:post:12",scan.alerts().getFirst().key());
        assertFalse(scan.articles().getFirst().body().contains("already read")); assertFalse(scan.articles().getFirst().body().contains("comment"));
        assertEquals(2,pages.stream().filter(p->p.equals("/category/portfolio-update/")).count());
        assertEquals(0,pages.stream().filter(p->p.equals("/news-feed/old/")).count());
    }
    @Test void changingArchiveRetriesOnceWithoutClaimingCoverage() throws Exception {
        int[] count={0};
        var source=new BravosSource((m,p,h,b)->ok(p.equals("/research/")?dashboard():p.startsWith("/news-feed/")?article("100"):card("/news-feed/"+(count[0]++)+"/","01/01/2026")));
        var scan=source.scan(LocalDate.of(2026,2,1),List.of());
        assertFalse(scan.complete()); assertTrue(scan.errors().contains("ARCHIVE_CHANGED_TWICE")); assertEquals(4,count[0]);
    }
    @Test void failedArticlesAndEmptyArchiveDoNotBecomeNoAlerts() throws Exception {
        var source=new BravosSource((m,p,h,b)->ok(p.equals("/research/")?dashboard():"logged out"));
        var scan=source.scan(LocalDate.now(),List.of(ORIGIN+"/news-feed/cf/"));
        assertFalse(scan.complete()); assertTrue(scan.errors().stream().anyMatch(e->e.startsWith("ARCHIVE_EMPTY"))); assertTrue(scan.errors().stream().anyMatch(e->e.startsWith("ARTICLE_REQUIRES")));
        assertThrows(IOException.class,()->new BravosSource((m,p,h,b)->new Transport.Response(403,"",Map.of())).scan(LocalDate.now(),List.of()));
    }
    @Test void redirectsBoundedAndDashboardNeverUsesQuant() throws Exception {
        var source=new BravosSource((m,p,h,b)->new Transport.Response(302,"",Map.of("location","/my-account/")));
        assertThrows(IOException.class,()->source.login("u","p"));
        assertTrue(source.dashboard(Jsoup.parse(dashboard().replace("Tactical Portfolio","Quant Portfolio"))).isEmpty());
        assertThrows(IllegalArgumentException.class,()->source.dashboard(Jsoup.parse(dashboard().replace("Long","Short"))));
        assertThrows(IllegalArgumentException.class,()->BravosSource.safePath("https://evil.example/x"));
        assertThrows(IllegalArgumentException.class,()->BravosSource.safePath("https://user@bravosresearch.com/x"));
        assertEquals("/x?q=1",BravosSource.safePath(ORIGIN+"/x?q=1"));
    }
}
