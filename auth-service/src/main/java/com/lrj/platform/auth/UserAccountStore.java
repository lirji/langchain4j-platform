package com.lrj.platform.auth;

import java.util.Optional;

/** 用户账号读取。接口 + 内存/JDBC 双实现（沿用项目"接口 + @ConditionalOnProperty 切换"主导写法）。 */
public interface UserAccountStore {

    Optional<UserAccount> findByUsername(String username);
}
