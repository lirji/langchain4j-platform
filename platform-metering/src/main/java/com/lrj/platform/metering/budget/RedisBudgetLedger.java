package com.lrj.platform.metering.budget;

import com.lrj.platform.metering.RedisDailyCounters;
import com.lrj.platform.metering.TokenBudgetProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** 原子预留/结算复用既有 token 日计数；Redis 不可用时抛错并拒绝模型调用。 */
public final class RedisBudgetLedger implements BudgetLedger {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local existing = redis.call('HGET', KEYS[3], 'reserved')
            if existing then
              if tonumber(existing) ~= tonumber(ARGV[1]) or redis.call('HGET', KEYS[3], 'actual') then return -2 end
              return 1
            end
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            local held = tonumber(redis.call('GET', KEYS[2]) or '0')
            local amount = tonumber(ARGV[1])
            if amount > tonumber(ARGV[2]) - used - held then return -1 end
            redis.call('INCRBY', KEYS[2], ARGV[1])
            redis.call('HSET', KEYS[3], 'reserved', ARGV[1])
            redis.call('PEXPIREAT', KEYS[2], ARGV[3])
            redis.call('PEXPIREAT', KEYS[3], ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> SETTLE = new DefaultRedisScript<>("""
            local reserved = redis.call('HGET', KEYS[3], 'reserved')
            if not reserved or tonumber(reserved) ~= tonumber(ARGV[1]) then return -2 end
            local actual = redis.call('HGET', KEYS[3], 'actual')
            if actual then
              if tonumber(actual) ~= tonumber(ARGV[2]) then return -2 end
              return 1
            end
            local held = tonumber(redis.call('GET', KEYS[2]) or '0')
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            if held < tonumber(reserved) or used < 0 then return -2 end
            redis.call('INCRBY', KEYS[1], ARGV[2])
            redis.call('DECRBY', KEYS[2], reserved)
            redis.call('HSET', KEYS[3], 'actual', ARGV[2])
            redis.call('PEXPIREAT', KEYS[1], ARGV[3])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final TokenBudgetProperties props;
    private final Clock clock;

    /** 只支持当前部署的单 Redis 主节点；多 key Lua 不宣称兼容 Redis Cluster。 */
    public RedisBudgetLedger(StringRedisTemplate redis, TokenBudgetProperties props, Clock clock) {
        this.redis = redis; this.props = props; this.clock = clock;
    }

    @Override
    public Reservation reserve(String tenant, String user, String operation, long tokens) {
        InMemoryBudgetLedger.validate(tenant, user, operation, tokens);
        var r = new Reservation(tenant, user, operation, LocalDate.now(clock).toString(), tokens);
        Long status = redis.execute(RESERVE, keys(r), Long.toString(tokens),
                Long.toString(props.resolveDailyBudget(tenant)), Long.toString(expires(r)));
        if (status == null) throw new IllegalStateException("budget store unavailable");
        if (status == -1) throw new Exceeded();
        if (status != 1) throw new Conflict();
        return r;
    }

    @Override
    public void settle(Reservation r, long actual) {
        InMemoryBudgetLedger.validate(r.tenantId(), r.userId(), r.operationId(), r.reservedTokens());
        if (actual < 0 || actual > 1000000) throw new IllegalArgumentException("invalid usage amount");
        // 日字段不是授权依据，仍需对应 owner/operation 的原始预留；拒绝不合理保留窗口。
        var day = LocalDate.parse(r.day());
        var today = LocalDate.now(clock);
        if (day.isAfter(today) || day.isBefore(today.minusDays(2))) throw new Conflict();
        Long status = redis.execute(SETTLE, keys(r), Long.toString(r.reservedTokens()),
                Long.toString(actual), Long.toString(expires(r)));
        if (status == null) throw new IllegalStateException("budget store unavailable");
        if (status != 1) throw new Conflict();
    }

    private List<String> keys(Reservation r) {
        String used = RedisDailyCounters.dayKey(props.getRedis().getKeyPrefix(), LocalDate.parse(r.day()), r.tenantId());
        String base = props.getRedis().getKeyPrefix() + "reservations:" + r.day() + ":" + digest(r.tenantId());
        return List.of(used, base + ":held", base + ":operation:" + digest(r.userId() + "\0" + r.operationId()));
    }

    private long expires(Reservation r) {
        return LocalDate.parse(r.day()).plusDays(3).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
