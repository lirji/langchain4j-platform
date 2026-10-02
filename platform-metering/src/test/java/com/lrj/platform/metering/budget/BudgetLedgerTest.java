package com.lrj.platform.metering.budget;

import com.lrj.platform.metering.InMemoryTokenBudgetTracker;
import com.lrj.platform.metering.TokenBudgetProperties;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class BudgetLedgerTest {
    private final TokenBudgetProperties props = props();
    private final InMemoryTokenBudgetTracker tracker = new InMemoryTokenBudgetTracker(props);
    private final BudgetLedger ledger = new InMemoryBudgetLedger(tracker, props, Clock.systemUTC());

    private TokenBudgetProperties props() {
        var p = new TokenBudgetProperties(); p.setTimezone("UTC"); p.getDailyTokens().setDefault(1000); return p;
    }

    @Test void existingUsageAndUnknownReservationsShareQuota() {
        tracker.consume("t1", 400);
        var r = ledger.reserve("t1", "u1", "native", 600);
        assertThat(ledger.reserve("t1", "u1", "native", 600)).isEqualTo(r);
        assertThatThrownBy(() -> ledger.reserve("t1", "u1", "python", 1)).isInstanceOf(BudgetLedger.Exceeded.class);
        ledger.settle(r, 300); ledger.settle(r, 300);
        assertThat(tracker.currentUsed("t1")).isEqualTo(700);
        assertThat(ledger.reserve("t1", "u1", "python", 300)).isNotNull();
        assertThatThrownBy(() -> ledger.reserve("t1", "u1", "native", 600)).isInstanceOf(BudgetLedger.Conflict.class);
    }

    @Test void ownerAndParametersCannotBeChangedForSettlement() {
        var r = ledger.reserve("t1", "u1", "op", 1000);
        assertThatThrownBy(() -> ledger.reserve("t1", "u1", "op", 900)).isInstanceOf(BudgetLedger.Conflict.class);
        assertThatThrownBy(() -> ledger.settle(new BudgetLedger.Reservation("t2", "u1", "op", r.day(), 1000), 0))
                .isInstanceOf(BudgetLedger.Conflict.class);
        assertThatThrownBy(() -> ledger.settle(new BudgetLedger.Reservation("t1", "u2", "op", r.day(), 1000), 0))
                .isInstanceOf(BudgetLedger.Conflict.class);
        assertThatThrownBy(() -> ledger.reserve("t1", "u2", "another", 1)).isInstanceOf(BudgetLedger.Exceeded.class);
        ledger.settle(r, 100);
        assertThatThrownBy(() -> ledger.settle(r, 0)).isInstanceOf(BudgetLedger.Conflict.class);
        assertThat(tracker.currentUsed("t1")).isEqualTo(100);
    }

    @Test void concurrentAdmissionCannotOversubscribe() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                String op = "op" + i;
                pool.submit(() -> {
                    try { start.await(); ledger.reserve("t1", "u1", op, 100); accepted.incrementAndGet(); }
                    catch (BudgetLedger.Exceeded expected) {}
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
            start.countDown();
        }
        assertThat(accepted).hasValue(10);
        assertThatThrownBy(() -> ledger.reserve("t1", "u1", "last", 1)).isInstanceOf(BudgetLedger.Exceeded.class);
    }

    @Test void usageOverEstimateIsFullyRecordedAndBlocksFurtherWork() {
        var r = ledger.reserve("t1", "u1", "op", 900);
        ledger.settle(r, 1100);
        assertThat(tracker.currentUsed("t1")).isEqualTo(1100);
        assertThatThrownBy(() -> ledger.reserve("t1", "u1", "another", 1)).isInstanceOf(BudgetLedger.Exceeded.class);
    }
}
