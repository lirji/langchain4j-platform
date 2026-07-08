package com.lrj.platform.conversation.memory.profile;

import dev.langchain4j.model.output.structured.Description;

/**
 * 单条抽取出的用户长期事实（迁移单体 {@code memory/profile/MemoryFact}）。
 */
public record MemoryFact(
        @Description("用第三人称陈述的关于用户的持久事实，如『偏好邮件联系』『是 Pro 套餐用户』") String text,
        @Description("类型之一：preference（偏好）| attribute（属性）| issue（长期诉求）| other") String type) {
}
