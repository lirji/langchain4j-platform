package com.lrj.platform.channel.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;

public final class TestInbox {
    private TestInbox() {}
    public static InboundInbox immediate() { return immediate(new InMemoryInboundInboxStore(100)); }
    public static InboundInbox immediate(InboundInboxStore store) {
        return new InboundInbox(store, new ObjectMapper(), Runnable::run, Clock.systemUTC(), 4, 3, 60000, 0, 180000);
    }
}
