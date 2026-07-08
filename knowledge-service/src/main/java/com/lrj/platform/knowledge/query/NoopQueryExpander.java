package com.lrj.platform.knowledge.query;

import java.util.List;

/**
 * 默认查询扩展：不扩展，只返回原 query。等价于「不做扩展」。
 */
public class NoopQueryExpander implements QueryExpander {

    @Override
    public List<String> expand(String query) {
        return List.of(query);
    }
}
