package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.lifecycle.PagedDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定分页/全量两个 GET 入口的 mapping 声明（{@link DocumentControllerPublicTest} 已覆盖直接委派行为，此处不重复）：
 * 旧 {@code list} 无 params 条件、返回数组；新 {@code listPaged} 有且仅有 {@code page} 条件、返回 {@link PagedDocuments} 信封。
 * 防止未来误删 {@code params="page"} 条件或改返回类型导致两入口塌成一个。不启动 MockMvc/Spring context。
 */
class DocumentControllerPagingMappingTest {

    @Test
    void pageParameterIsTheOnlyConditionSelectingPagedEnvelope() throws NoSuchMethodException {
        Method legacy = DocumentController.class.getDeclaredMethod("list", String.class);
        Method paged = DocumentController.class.getDeclaredMethod(
                "listPaged", String.class, int.class, Integer.class);

        GetMapping legacyMapping = legacy.getAnnotation(GetMapping.class);
        GetMapping pagedMapping = paged.getAnnotation(GetMapping.class);

        assertThat(legacyMapping).isNotNull();
        assertThat(legacyMapping.params()).isEmpty();
        assertThat(legacy.getReturnType()).isEqualTo(List.class);

        assertThat(pagedMapping).isNotNull();
        assertThat(pagedMapping.params()).containsExactly("page");
        assertThat(paged.getReturnType()).isEqualTo(PagedDocuments.class);

        RequestParam pageParam = paged.getParameters()[1].getAnnotation(RequestParam.class);
        RequestParam sizeParam = paged.getParameters()[2].getAnnotation(RequestParam.class);
        assertThat(pageParam).isNotNull();
        assertThat(pageParam.defaultValue()).isEqualTo("1");
        assertThat(sizeParam).isNotNull();
        assertThat(sizeParam.required()).isFalse();
    }
}
