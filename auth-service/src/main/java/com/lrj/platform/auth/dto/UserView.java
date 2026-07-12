package com.lrj.platform.auth.dto;

import java.util.List;

/** 对前端暴露的当前用户视图（不含任何密码/令牌信息）。 */
public record UserView(String username, String tenant, List<String> scopes) {
}
