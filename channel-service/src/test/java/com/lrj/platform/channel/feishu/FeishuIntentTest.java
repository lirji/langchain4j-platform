package com.lrj.platform.channel.feishu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 意图分类纯函数：退款/投诉类关键词 → WORKFLOW，其余 → CHAT。移植单体 FeishuIntentTest。 */
class FeishuIntentTest {

    @Test
    void refundKeywords_routeToWorkflow() {
        assertThat(FeishuIntent.classify("我要退款")).isEqualTo(FeishuIntent.Route.WORKFLOW);
        assertThat(FeishuIntent.classify("申请退货处理")).isEqualTo(FeishuIntent.Route.WORKFLOW);
        assertThat(FeishuIntent.classify("我要投诉你们")).isEqualTo(FeishuIntent.Route.WORKFLOW);
        assertThat(FeishuIntent.classify("I want a refund")).isEqualTo(FeishuIntent.Route.WORKFLOW);
        assertThat(FeishuIntent.classify("请求 chargeback")).isEqualTo(FeishuIntent.Route.WORKFLOW);
    }

    @Test
    void nonRefundMessages_routeToChat() {
        assertThat(FeishuIntent.classify("今天天气怎么样")).isEqualTo(FeishuIntent.Route.CHAT);
        assertThat(FeishuIntent.classify("介绍一下你们的产品")).isEqualTo(FeishuIntent.Route.CHAT);
    }

    @Test
    void nullOrBlank_routeToChat() {
        assertThat(FeishuIntent.classify(null)).isEqualTo(FeishuIntent.Route.CHAT);
        assertThat(FeishuIntent.classify("   ")).isEqualTo(FeishuIntent.Route.CHAT);
    }
}
