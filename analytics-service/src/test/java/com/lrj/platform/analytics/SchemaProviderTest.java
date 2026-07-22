package com.lrj.platform.analytics;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaProviderTest {

    @Test
    void introspectionIsRestrictedToCurrentCatalogAndDeduplicatesColumns() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = columns(new String[][]{
                {"id", "INT", "primary"},
                {"id", "INT", "duplicate metadata row"},
                {"tenant_id", "VARCHAR", "tenant"}
        });
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("nl2sql_demo");
        when(connection.getSchema()).thenReturn(null);
        when(metadata.getColumns(eq("nl2sql_demo"), isNull(), anyString(), isNull())).thenReturn(columns);

        Nl2SqlProperties properties = new Nl2SqlProperties();
        properties.setAllowTables(List.of("orders"));
        SchemaProvider provider = new SchemaProvider(dataSource, properties);

        assertThat(provider.describe("orders"))
                .contains("id INT")
                .contains("tenant_id VARCHAR");
        assertThat(count(provider.describe("orders"), "  - id INT")).isEqualTo(1);
        verify(metadata, never()).getColumns(isNull(), isNull(), anyString(), isNull());
    }

    private static ResultSet columns(String[][] rows) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(resultSet.next()).thenAnswer(invocation -> index.incrementAndGet() < rows.length);
        when(resultSet.getString(anyString())).thenAnswer(invocation -> {
            int column = switch (invocation.getArgument(0, String.class)) {
                case "COLUMN_NAME" -> 0;
                case "TYPE_NAME" -> 1;
                case "REMARKS" -> 2;
                default -> throw new IllegalArgumentException("unexpected metadata column");
            };
            return rows[index.get()][column];
        });
        return resultSet;
    }

    private static int count(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
