package com.lrj.platform.metering.budget;

import com.lrj.platform.metering.TokenBudgetProperties;
import com.lrj.platform.metering.TokenBudgetTracker;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** 单 JVM 开发实现，与现有 tracker 共用已用量；生产多副本必须选择 Redis。 */
public final class InMemoryBudgetLedger implements BudgetLedger {
    private final TokenBudgetTracker tracker;
    private final TokenBudgetProperties props;
    private final Clock clock;
    private LocalDate lastCleanup;
    private final Map<Reservation, Long> settled = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final Map<String, Long> held = new HashMap<>();
    private final Map<String, Long> historicalUsage = new HashMap<>();

    /** 时钟可注入，日边界与现有预算使用相同配置时区。 */
    public InMemoryBudgetLedger(TokenBudgetTracker tracker, TokenBudgetProperties props, Clock clock) {
        this.tracker = tracker; this.props = props; this.clock = clock;
    }

    @Override
    public synchronized Reservation reserve(String tenant, String user, String operation, long tokens) {
        validate(tenant, user, operation, tokens);
        String day = LocalDate.now(clock).toString();
        // 每天最多清理一次，不能每次准入都线性扫描整个租户账本。
        if (!LocalDate.now(clock).equals(lastCleanup)) {
            String cutoff = LocalDate.now(clock).minusDays(2).toString();
            reservations.entrySet().removeIf(e -> e.getValue().day().compareTo(cutoff) < 0);
            settled.keySet().removeIf(r -> r.day().compareTo(cutoff) < 0);
            held.keySet().removeIf(k -> k.substring(0, 10).compareTo(cutoff) < 0);
            historicalUsage.keySet().removeIf(k -> k.substring(0, 10).compareTo(cutoff) < 0);
            lastCleanup = LocalDate.now(clock);
        }
        var r = new Reservation(tenant, user, operation, day, tokens);
        String key = key(r);
        var previous = reservations.get(key);
        if (previous != null) {
            if (!previous.equals(r) || settled.containsKey(r)) throw new Conflict();
            return previous;
        }
        if (reservations.size() >= 10000) throw new Exceeded();
        String dayKey = dayKey(r);
        long used = tracker.currentUsed(tenant);
        long reserved = held.getOrDefault(dayKey, 0L);
        long budget = props.resolveDailyBudget(tenant);
        if (tokens > budget || used > budget - tokens || reserved > budget - tokens - used) throw new Exceeded();
        held.put(dayKey, reserved + tokens); reservations.put(key, r);
        return r;
    }

    @Override
    public synchronized void settle(Reservation r, long actual) {
        validate(r.tenantId(), r.userId(), r.operationId(), r.reservedTokens());
        if (actual < 0 || actual > 1000000 || !r.equals(reservations.get(key(r)))) throw new Conflict();
        Long prior = settled.get(r);
        if (prior != null) {
            if (prior != actual) throw new Conflict();
            return;
        }
        if (r.day().equals(LocalDate.now(clock).toString())) tracker.consume(r.tenantId(), actual);
        else historicalUsage.merge(dayKey(r), actual, Long::sum);
        held.merge(dayKey(r), -r.reservedTokens(), Long::sum);
        settled.put(r, actual);
    }

    static void validate(String tenant, String user, String op, long tokens) {
        validateIdentity(tenant, 256);
        validateIdentity(user, 256);
        validateIdentity(op, 128);
        if (tokens <= 0 || tokens > 1000000) throw new IllegalArgumentException("invalid reservation amount");
    }

    private static void validateIdentity(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("invalid budget identity");
    }

    private static String key(Reservation r) { return r.day() + "\0" + r.tenantId() + "\0" + r.userId() + "\0" + r.operationId(); }
    private static String dayKey(Reservation r) { return r.day() + "\0" + r.tenantId(); }
}
