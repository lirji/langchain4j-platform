package com.lrj.platform.conversation.memory.profile;

import com.lrj.platform.conversation.Assistant;
import com.lrj.platform.conversation.prompt.ResolvedAssistantStyle;

/**
 * 带长期记忆的对话（迁移单体 {@code UserProfileChatService}）：回忆用户画像 → 作为 {@code @V("context")}
 * 新鲜注入系统提示（不落多轮记忆，避免前缀在历史里累积）→ 观察本轮以更新画像（用原始消息，不含前缀）。
 */
public class UserProfileChatService {

    private static final String PROFILE_HEADER =
            "【关于该用户的长期记忆（跨会话背景，相关才用，无关请忽略）】\n";

    private final Assistant assistant;
    private final ResolvedAssistantStyle style;
    private final UserProfileService profileService;

    public UserProfileChatService(Assistant assistant, ResolvedAssistantStyle style,
                                  UserProfileService profileService) {
        this.assistant = assistant;
        this.style = style;
        this.profileService = profileService;
    }

    public String chat(String tenant, String user, String chatId, String message) {
        String memoryKey = tenant + "::" + chatId;
        String profile = profileService.recall(tenant, user);
        String context = profile.isBlank() ? "" : PROFILE_HEADER + profile;
        String reply = assistant.chat(memoryKey, style.getLanguage(), style.getTone(),
                style.getCitationPolicy(), style.getExtra(), message, context);
        profileService.observe(tenant, user, chatId, message, reply);
        return reply;
    }
}
