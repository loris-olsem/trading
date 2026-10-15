package com.loris.bravos.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OrderAuditTest {
  @Test
  void salesUseCloseOrderLookupInsteadOfOpeningLookup() {
    for (String action : java.util.List.of("CLOSE", "REDUCE", "EARLY_EXIT"))
      assertEquals(
          "/api/v1/trading/info/real/close-orders/123", OrderAudit.lookupPath(action, 123));
    for (String action : java.util.List.of("OPEN", "ADD"))
      assertEquals(
          "/api/v2/trading/info/orders:lookup?orderId=123", OrderAudit.lookupPath(action, 123));
  }
}
