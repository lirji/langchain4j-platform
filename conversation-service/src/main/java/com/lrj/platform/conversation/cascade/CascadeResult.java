package com.lrj.platform.conversation.cascade;

/**
 * 级联结果（{@code POST /chat/cascade} 响应）。{@code served} 让「这次省没省钱」一眼可见。
 *
 * @param question       原问题
 * @param answer         最终答案
 * @param served         "cheap" | "strong"，谁作答（成本可见）
 * @param cheapConfident 便宜模型是否被判置信（false = 发生了升级）
 * @param tenantId       发起租户（多租户归因）
 */
public record CascadeResult(String question,
                            String answer,
                            String served,
                            boolean cheapConfident,
                            String tenantId) {
}
