package com.lrj.platform.agent.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnMissingBean(AnalyticsClient.class)
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class NoopAnalyticsClient implements AnalyticsClient {

    @Override
    public Result ask(String question) {
        return new Result(question, null, 0, List.of(), null, false,
                "analytics action disabled");
    }
}
