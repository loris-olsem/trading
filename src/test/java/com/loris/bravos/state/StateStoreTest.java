package com.loris.bravos.state;

import com.loris.bravos.domain.Policy;
import com.loris.bravos.util.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    @TempDir Path temp;
    @Test void exclusiveClaimAtomicGenerationsAndHistorySurviveRestart() throws Exception {
        try(var store=new StateStore(temp)) {
            assertThrows(IOException.class,()->new StateStore(temp));
            assertEquals(0,store.state().generation); assertFalse(store.killed());
            store.state().book.cycles.put("opening",cycle()); store.save();
            assertEquals(1,store.state().generation);
            byte[] previous=Files.readAllBytes(temp.resolve("ledger.json"));
            store.state().report.add("test"); store.save();
            assertArrayEquals(previous,Files.readAllBytes(temp.resolve("history/1.json")));
            assertEquals(2,Json.MAPPER.readTree(temp.resolve("ledger.json").toFile()).get("generation").asLong());
            Files.createFile(temp.resolve("KILL")); assertTrue(store.killed());
        }
        try(var store=new StateStore(temp)) { assertEquals("test",store.state().report.getFirst()); assertEquals("CF",store.state().book.cycles.get("opening").symbol); }
    }
    @Test void malformedStateAndBrokenReferencesFailWithoutReplacement() throws Exception {
        Path ledger=temp.resolve("ledger.json"); Files.writeString(ledger,"{broken");
        assertThrows(IOException.class,()->new StateStore(temp)); assertEquals("{broken",Files.readString(ledger));
        Files.writeString(ledger,"{\"schemaVersion\":2}"); assertThrows(IOException.class,()->new StateStore(temp));
        Files.delete(ledger);
        try(var store=new StateStore(temp)) {
            var intent=new Policy().opening(cycle(),instrument(),quote("100"),account(),NOW,d("0")).intents().getFirst();
            var a=new TradingState.Attempt(intent,NOW); store.state().attempts.put(intent.key(),a);
            assertThrows(IOException.class,store::save);
            store.state().book.cycles.put("opening",cycle()); a.reference="bad"; assertThrows(IOException.class,store::save);
        }
    }
    @Test void historyCollisionCannotOverwriteEvidence() throws Exception {
        try(var store=new StateStore(temp)) {
            store.save(); Files.createDirectories(temp.resolve("history")); Files.writeString(temp.resolve("history/1.json"),"different");
            assertThrows(IOException.class,store::save);
            assertEquals(1,store.state().generation);
        }
    }
}
