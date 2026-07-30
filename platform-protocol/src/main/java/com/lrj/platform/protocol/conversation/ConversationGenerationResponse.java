package com.lrj.platform.protocol.conversation;

/** 独立 conversation runtime 的候选生成结果；不携带任何会话或领域状态。 */
public record ConversationGenerationResponse(String reply) {}
