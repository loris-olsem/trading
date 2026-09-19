package com.loris.bravos.source;

import com.loris.bravos.broker.Transport;
import com.loris.bravos.domain.Model.Alert;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** HTML acquisition is independent of deterministic trade interpretation. */
public final class BravosSource {
    public static final String ARCHIVE="/category/portfolio-update/";
    private static final String ORIGIN="https://bravosresearch.com";
    private final Transport transport;
    private final AlertParser parser=new AlertParser();
    public record Article(String key,String url,LocalDate date,String title,String body) {}
    public record Scan(List<Article> articles,List<Alert> alerts,List<String> errors,List<String> pages,boolean complete,Map<String,java.math.BigDecimal> dashboard) {}
    public BravosSource(Transport transport) { this.transport=transport; }
    public void login(String username,String password) throws IOException {
        Document page=html(get("/my-account/"),"/my-account/");
        if(page.select("a[href*=customer-logout]").size()>0) return;
        Element form=page.select("form:has(input[type=password])").first();
        if(form==null) throw new IOException("BRAVOS_LOGIN_FORM_UNAVAILABLE");
        String action=form.attr("action").isBlank()?"/my-account/":safePath(form.absUrl("action"));
        Map<String,String> fields=new LinkedHashMap<>();
        for(Element e:form.select("input[type=hidden][name],button[name]")) fields.put(e.attr("name"),e.attr("value"));
        Element pass=form.selectFirst("input[type=password][name]");
        Element user=form.selectFirst("input[name=username],input[name=log],input[type=email][name]");
        if(user==null || pass==null) throw new IOException("UNSUPPORTED_LOGIN_FORM");
        fields.put(user.attr("name"),username); fields.put(pass.attr("name"),password);
        String encoded=fields.entrySet().stream().map(e->url(e.getKey())+"="+url(e.getValue())).reduce((a,b)->a+"&"+b).orElseThrow();
        var response=transport.request("POST",action,Map.of("Content-Type","application/x-www-form-urlencoded"),encoded);
        if(response.status()>=300 && response.status()<400) get(safePath(URI.create(ORIGIN+action).resolve(response.headers().getOrDefault("location","/my-account/")).toString()));
        else if(response.status()!=200) throw new IOException("BRAVOS_LOGIN_FAILED");
        Document check=html(get("/my-account/"),"/my-account/");
        if(check.select("a[href*=customer-logout]").isEmpty()) throw new IOException("BRAVOS_LOGIN_NOT_CONFIRMED");
    }
    public Scan scan(LocalDate floor,Collection<String> rereadUrls) throws IOException {
        for(int retry=0;retry<2;retry++) {
            Map<String,String> entries=new LinkedHashMap<>(); List<String> pages=new ArrayList<>();
            List<String> errors=new ArrayList<>(); String first=null;
            boolean boundary=false;
            for(int p=1;p<=500;p++) {
                String path=p==1?ARCHIVE:ARCHIVE+"page/"+p+"/";
                Document page=html(get(path),path); pages.add(ORIGIN+path);
                List<Entry> found=archive(page);
                if(found.isEmpty()) { errors.add("ARCHIVE_EMPTY_OR_CHANGED:"+p); break; }
                String signature=found.stream().map(Entry::url).reduce("",(a,b)->a+"|"+b);
                if(p==1) first=signature;
                for(Entry entry:found) if(!entry.date().isBefore(floor)) entries.put(entry.url(),entry.url());
                if(found.stream().allMatch(e->e.date().isBefore(floor))) { boundary=true; break; }
            }
            String recheck=archive(html(get(ARCHIVE),ARCHIVE)).stream().map(Entry::url).reduce("",(a,b)->a+"|"+b);
            if(first!=null && !Objects.equals(first,recheck)) {
                if(retry==0) continue;
                errors.add("ARCHIVE_CHANGED_TWICE"); boundary=false;
            }
            for(String u:rereadUrls) entries.put(u,u);
            List<Article> articles=new ArrayList<>(); List<Alert> alerts=new ArrayList<>();
            for(String u:entries.values()) {
                try {
                    Article a=article(html(get(safePath(u)),safePath(u)),u); articles.add(a);
                    alerts.add(parser.parse(a.key(),a.url(),a.date(),a.title(),a.body()));
                } catch(IllegalArgumentException|IOException e) { errors.add("ARTICLE_REQUIRES_REVIEW:"+u); }
            }
            Map<String,java.math.BigDecimal> dashboard=dashboard(html(get("/research/"),"/research/"));
            if(dashboard.isEmpty()) errors.add("DASHBOARD_UNAVAILABLE");
            return new Scan(List.copyOf(articles),List.copyOf(alerts),List.copyOf(errors),List.copyOf(pages),boundary && errors.isEmpty(),dashboard);
        }
        throw new IOException("DISCOVERY_FAILED");
    }
    public record Entry(String url,LocalDate date) {}
    public List<Entry> archive(Document doc) {
        List<Entry> result=new ArrayList<>();
        // Bravos's observed archive uses .right content cards.
        for(Element card:doc.select(".right")) {
            Element link=card.select("a[href]").stream().filter(a->a.text().length()>15).findFirst().orElse(null);
            Matcher date=Pattern.compile("\\b(\\d{2}/\\d{2}/\\d{4})\\b").matcher(card.text());
            if(link!=null && date.find()) result.add(new Entry(ORIGIN+safePath(link.absUrl("href")),parseDate(date.group(1))));
        }
        return result.stream().distinct().toList();
    }
    public Article article(Document doc,String url) {
        Element article=doc.select("article[id^=post-]:has(.entry-content)").first();
        if(article==null) throw new IllegalArgumentException("ARTICLE_BODY_MISSING");
        Element title=article.selectFirst("h1");
        if(title==null) title=doc.selectFirst("h1");
        Element content=article.selectFirst(".entry-content").clone();
        content.select("script,style,.comments-area,#comments,button").remove();
        String body=content.wholeText().replace('\u00a0',' ');
        Matcher date=Pattern.compile("\\b(\\d{2}/\\d{2}/\\d{4})\\b").matcher(body);
        if(!date.find() || title==null) throw new IllegalArgumentException("ARTICLE_METADATA_MISSING");
        LocalDate published=parseDate(date.group(1));
        Matcher instruction=Pattern.compile("(?m)^\\s*We(?: (?:are|have)|[’']re)\\b").matcher(body);
        if(!instruction.find()) throw new IllegalArgumentException("AUTHORED_INSTRUCTION_MISSING");
        body=body.substring(instruction.start()).trim();
        return new Article("bravos:"+article.id().replace("post-","post:"),url,published,title.text(),body);
    }
    public Map<String,java.math.BigDecimal> dashboard(Document doc) {
        Map<String,java.math.BigDecimal> result=new LinkedHashMap<>();
        Element section=doc.select(".dash-newsletter").stream().filter(e->e.select(".h4").text().startsWith("Tactical Portfolio")).findFirst().orElse(null);
        if(section==null) return result;
        for(Element row:section.select(".asset-ratings .tbody .tr")) {
            var cells=row.select(".td");
            if(cells.size()!=3 || !cells.get(1).text().equals("Long")) throw new IllegalArgumentException("DASHBOARD_FORMAT_OR_DIRECTION_CHANGED");
            Matcher symbol=Pattern.compile("\\(\\$([A-Z][A-Z0-9.]{0,12})\\)").matcher(cells.getFirst().text());
            if(!symbol.find()) throw new IllegalArgumentException("DASHBOARD_SYMBOL_MISSING");
            String ticker=symbol.group(1);
            String weight=cells.getLast().text().replace("%","").trim();
            if(!weight.matches("\\d+(?:\\.\\d+)?") || result.containsKey(ticker)) throw new IllegalArgumentException("DASHBOARD_WEIGHT_INVALID");
            result.put(ticker,new java.math.BigDecimal(weight));
        }
        return result;
    }
    private String get(String path) throws IOException {
        for(int redirects=0;redirects<=3;redirects++) {
            var response=transport.request("GET",path,Map.of(),null);
            if(response.status()==200) return response.body();
            if(!Set.of(301,302,303,307,308).contains(response.status())) throw new IOException("BRAVOS_READ_FAILED");
            path=safePath(URI.create(ORIGIN+path).resolve(response.headers().getOrDefault("location","")).toString());
        }
        throw new IOException("BRAVOS_REDIRECT_LIMIT");
    }
    private static Document html(String body,String path) { return Jsoup.parse(body,ORIGIN+path); }
    private static LocalDate parseDate(String value) { return LocalDate.parse(value,DateTimeFormatter.ofPattern("MM/dd/uuuu")); }
    public static String safePath(String address) {
        URI uri=URI.create(address);
        if(!"https".equals(uri.getScheme()) || !"bravosresearch.com".equals(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null) throw new IllegalArgumentException("SOURCE_ORIGIN_ESCAPE");
        return uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
    }
    private static String url(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
}
