package com.lrj.platform.analytics.controller;

import com.lrj.platform.analytics.SchemaProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 探表端点：白名单表返回结构，非白名单表 404（不泄露存在性）。SchemaProvider 用 mock —— 不触真实 DB 内省。
 */
class AnalyticsSchemaControllerTest {

    @Test
    void tables_returnsWhitelistedTableNames() throws Exception {
        SchemaProvider schema = mock(SchemaProvider.class);
        when(schema.tableNames()).thenReturn(List.of("orders", "customers", "refunds"));

        standaloneSetup(new AnalyticsSchemaController(schema)).build()
                .perform(get("/analytics/schema/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0]").value("orders"))
                .andExpect(jsonPath("$.tables.length()").value(3));
    }

    @Test
    void describe_returnsSchemaBlockForWhitelistedTable() throws Exception {
        SchemaProvider schema = mock(SchemaProvider.class);
        when(schema.describe("orders")).thenReturn("Table orders\n  - id BIGINT\n  - status VARCHAR");

        standaloneSetup(new AnalyticsSchemaController(schema)).build()
                .perform(get("/analytics/schema/tables/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table").value("orders"))
                .andExpect(jsonPath("$.schema").value("Table orders\n  - id BIGINT\n  - status VARCHAR"));
    }

    @Test
    void describe_returns404ForNonWhitelistedTable() throws Exception {
        SchemaProvider schema = mock(SchemaProvider.class);
        when(schema.describe("secret")).thenReturn(null);

        standaloneSetup(new AnalyticsSchemaController(schema)).build()
                .perform(get("/analytics/schema/tables/secret"))
                .andExpect(status().isNotFound());
    }
}
