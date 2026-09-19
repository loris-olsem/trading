package com.loris.bravos.app;

import com.loris.bravos.broker.*;
import com.loris.bravos.source.*;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.state.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

/** User-run application. With no arguments it displays usage and makes no requests. */
public final class Main {
    private Main() {}
    public static void main(String[] args) {
        int code=run(args,Path.of("").toAbsolutePath(),Clock.systemUTC(),System.out);
        if(code!=0) System.exit(code);
    }
    public static int run(String[] args,Path root,Clock clock,PrintStream out) {
        try {
            if(args.length==0 || args[0].equals("help")) {
                out.println("Bravos: plan [--since YYYY-MM-DD] | initialize [--since YYYY-MM-DD] | run | status | early-exit CYCLE FRACTION\nRun submits eligible trades. Plan and initialize never submit. Early-exit records a request for the next run.\nConfiguration: config/trading.json. State: state/runtime. Create state/runtime/KILL to stop submissions."); return 0;
            }
            String command=args[0];
            if(!Set.of("plan","initialize","run","status","early-exit").contains(command)) throw new IOException("UNKNOWN_COMMAND");
            LocalDate since=LocalDate.now(clock.withZone(ZoneId.of("Europe/Luxembourg"))).minusDays(29);
            if(command.equals("plan") || command.equals("initialize")) {
                if(args.length==3 && args[1].equals("--since")) since=LocalDate.parse(args[2]);
                else if(args.length!=1) throw new IOException("INVALID_ARGUMENTS");
                if(since.isAfter(LocalDate.now(clock))) throw new IOException("FUTURE_ENROLLMENT_DATE");
            } else if(!command.equals("early-exit") && args.length!=1) throw new IOException("INVALID_ARGUMENTS");
            try(StateStore store=new StateStore(root.resolve("state/runtime"))) {
                if(command.equals("status")) {
                    out.println("generation="+store.state().generation+" initialized="+(store.state().enrollmentFloor!=null)+" checkpoint="+store.state().checkpoint+" killed="+store.killed());
                    store.state().report.forEach(out::println);
                    for(var a:store.state().attempts.values()) out.println(a.intent.key()+" "+a.status+" "+a.result);
                    return 0;
                }
                if(command.equals("early-exit")) {
                    if(args.length!=3) throw new IOException("EARLY_EXIT_REQUIRES_CYCLE_AND_FRACTION");
                    var cycle=store.state().book.cycles.get(args[1]);
                    BigDecimal fraction=new BigDecimal(args[2]);
                    if(cycle==null || !cycle.entered || cycle.terminal || fraction.signum()<=0 || fraction.compareTo(BigDecimal.ONE)>0) throw new IOException("INVALID_EARLY_EXIT");
                    if(store.state().earlyExits.stream().anyMatch(e->e.symbol().equals(cycle.key) && !cycle.completed.contains(e.key()))) throw new IOException("EARLY_EXIT_ALREADY_PENDING");
                    store.state().earlyExits.add(new Alert("user:"+UUID.randomUUID(),"user-request",LocalDate.now(clock),"user-request",cycle.key,Action.EARLY_EXIT,null,BigDecimal.ONE,BigDecimal.ONE.subtract(fraction),null,List.of()));
                    store.save(); out.println("Early exit recorded. The next user-run `run` will process it."); return 0;
                }
                if(command.equals("run") && store.state().enrollmentFloor==null) throw new IOException("INITIALIZE_REQUIRED");
                if(command.equals("initialize") && store.state().enrollmentFloor!=null) throw new IOException("ALREADY_INITIALIZED");
                Configuration config=Configuration.load(root.resolve("config/trading.json"));
                Secrets secrets=Secrets.load(root);
                var broker=new EtoroClient(new HttpTransport(URI.create("https://public-api.etoro.com"),false),secrets,config,clock,command.equals("run"));
                var workflow=new Workflow(store,broker,broker,clock);
                var source=new BravosSource(new HttpTransport(URI.create("https://bravosresearch.com"),true));
                source.login(secrets.username(),secrets.password());
                LocalDate floor=store.state().checkpoint==null?since.minusDays(90):store.state().checkpoint.atZone(ZoneId.of("Europe/Luxembourg")).toLocalDate();
                Set<String> urls=new LinkedHashSet<>();
                boolean audit=store.state().revisionAudit==null || Duration.between(store.state().revisionAudit,clock.instant()).compareTo(Duration.ofDays(7))>=0;
                for(var c:store.state().book.cycles.values()) if(c.sourceOpen || c.blocker!=null || audit) for(var e:c.events) urls.add(e.url());
                BravosSource.Scan scan=source.scan(floor,urls);
                // Acquisition backfill is separate from the enrollment window.
                for(int retry=0;store.state().checkpoint==null && scan.complete() && !Workflow.dashboardMatches(Workflow.initialBook(scan.alerts(),since),scan.dashboard()) && retry<8;retry++) {
                    floor=floor.minusDays(90); scan=source.scan(floor,urls);
                }
                Instant previousAudit=store.state().revisionAudit;
                workflow.acceptScan(scan,since,command.equals("initialize"));
                if(!audit) store.state().revisionAudit=previousAudit;
                if(command.equals("initialize")) { store.save(); out.println("Initialized enrollment since "+since+"; no orders submitted."); return 0; }
                workflow.evaluate(command.equals("run")).forEach(out::println);
                return 0;
            }
        } catch(Exception e) {
            // Never print HTTP/library exception bodies or credential-bearing causes.
            String code=e.getMessage();
            out.println(code!=null && code.matches("[A-Z][A-Z0-9_]{2,100}")?code:"OPERATION_FAILED_NO_AUTOMATIC_RETRY");
            return 1;
        }
    }
}
