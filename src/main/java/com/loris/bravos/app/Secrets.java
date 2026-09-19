package com.loris.bravos.app;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Values are never part of records, diagnostics, or exception messages. */
public final class Secrets {
    private final String application,agent,owner,user,password;
    public Secrets(String application,String agent,String owner,String user,String password) {
        for(String s:List.of(application,agent,owner,user,password)) if(s.isBlank()) throw new IllegalArgumentException("EMPTY_CREDENTIAL");
        this.application=application; this.agent=agent; this.owner=owner; this.user=user; this.password=password;
    }
    public static Secrets load(Path root) throws IOException {
        try { return new Secrets(read(root,"etoro-bravos-agent/bravos-public-key.txt"),read(root,"etoro-bravos-agent/bravos-private-key.txt"),read(root,"etoro-main-readonly/private-key.txt"),read(root,"bravos/username.txt"),read(root,"bravos/password.txt")); }
        catch(Exception e) { throw new IOException("CREDENTIAL_FILES_UNAVAILABLE"); }
    }
    private static String read(Path root,String path) throws IOException { return Files.readString(root.resolve("secrets").resolve(path)).replace("\ufeff","").trim(); }
    public Map<String,String> headers(boolean ownerRead,String reference) {
        return Map.of("x-api-key",application,"x-user-key",ownerRead?owner:agent,"x-request-id",reference,"Content-Type","application/json");
    }
    public String username() { return user; }
    public String password() { return password; }
    @Override public String toString() { return "Secrets[REDACTED]"; }
}
