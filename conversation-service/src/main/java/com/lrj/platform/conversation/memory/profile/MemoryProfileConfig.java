package com.lrj.platform.conversation.memory.profile;

import com.lrj.platform.conversation.Assistant;
import com.lrj.platform.conversation.prompt.ResolvedAssistantStyle;
import com.lrj.platform.gateway.GatewayChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 长期记忆/用户画像装配（对齐单体 {@code MemoryProfileConfig}）。默认关；开启后构建内存画像存储、
 * temp=0 抽取器、观察线程池，以及 recall/observe 编排与带记忆对话服务。
 */
@Configuration
@ConditionalOnProperty(name = "app.conversation.memory.profile.enabled", havingValue = "true")
public class MemoryProfileConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryProfileConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.conversation.memory.profile.store", havingValue = "in-memory",
            matchIfMissing = true)
    UserProfileStore userProfileStore(@Value("${app.conversation.memory.profile.max-items:50}") int maxItems) {
        log.info("User profile store: in-memory (maxItems={})", maxItems);
        return new InMemoryUserProfileStore(maxItems);
    }

    @Bean
    ProfileExtractor profileExtractor(GatewayChatModelFactory factory) {
        ChatModel judge = factory.buildDeterministic();
        return AiServices.builder(ProfileExtractor.class).chatModel(judge).build();
    }

    @Bean
    Executor profileExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("profile-observe-");
        executor.initialize();
        return executor;
    }

    @Bean
    UserProfileService userProfileService(
            UserProfileStore store, ProfileExtractor extractor, Executor profileExecutor,
            @Value("${app.conversation.memory.profile.recall-limit:12}") int recallLimit,
            @Value("${app.conversation.memory.profile.async:true}") boolean async) {
        log.info("User profile: enabled (recallLimit={}, async={})", recallLimit, async);
        return new UserProfileService(store, extractor, profileExecutor, recallLimit, async,
                System::currentTimeMillis);
    }

    @Bean
    UserProfileChatService userProfileChatService(Assistant assistant, ResolvedAssistantStyle style,
                                                  UserProfileService userProfileService) {
        return new UserProfileChatService(assistant, style, userProfileService);
    }
}
