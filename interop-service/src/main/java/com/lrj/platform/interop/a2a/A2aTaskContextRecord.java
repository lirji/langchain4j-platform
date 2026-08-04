package com.lrj.platform.interop.a2a;

import java.time.Instant;

/**
 * 语言中立的 A2A task/context 持久记录。push token 只能进入密文字段，绝不存明文或内部身份令牌。
 */
public record A2aTaskContextRecord(String schemaVersion,
                                   long revision,
                                   String tenantId,
                                   String userId,
                                   String taskId,
                                   String contextId,
                                   String skill,
                                   String messageId,
                                   String pushUrl,
                                   String pushTokenCiphertext,
                                   String pushConfigId,
                                   Instant createdAt,
                                   Instant updatedAt,
                                   Instant expiresAt) {
}
