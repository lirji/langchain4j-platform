package com.lrj.platform.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class PlusMapperFactoryTest {
    @TableName("SAMPLE")
    public static class Sample {
        @TableId public String id;
        public String tenant;
        public String content;
    }
    public interface SampleMapper extends BaseMapper<Sample> {
        @org.apache.ibatis.annotations.Select("SELECT CONTENT FROM SAMPLE WHERE ID=#{id}")
        Sample contentOnly(@org.apache.ibatis.annotations.Param("id") String id);
    }
    @Test void nullProjectionStillRepresentsAnExistingRow() {
        var mapper = PlusMapperFactory.create(source(), SampleMapper.class);
        var row = new Sample(); row.id = "null-content"; mapper.insert(row);
        assertThat(mapper.contentOnly(row.id)).isNotNull();
        assertThat(mapper.contentOnly(row.id).content).isNull();
        assertThat(mapper.contentOnly("missing")).isNull();
    }
    @Test void realBaseMapperSharesTransactionsAndKeepsDataSourcesIsolated() {
        var first = source(); var second = source();
        var mapper = PlusMapperFactory.create(first, SampleMapper.class);
        var independent = PlusMapperFactory.create(second, SampleMapper.class);
        var row = new Sample(); row.id = "one"; row.tenant = "tenant-a"; row.content = "before";
        mapper.insert(row);
        assertThat(mapper.selectById("one").content).isEqualTo("before");
        assertThat(independent.selectById("one")).isNull();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(first));
        tx.executeWithoutResult(status -> {
            row.content = "after"; mapper.updateById(row);
            assertThat(mapper.selectById("one").content).isEqualTo("after");
            assertThat(PlusMapperFactory.create(first, SampleMapper.class).selectById("one").content).isEqualTo("after");
            status.setRollbackOnly();
        });
        assertThat(mapper.selectById("one").content).isEqualTo("before");
        assertThatThrownBy(() -> mapper.insert(row)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
    private static DriverManagerDataSource source() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:mp-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(ds).execute("CREATE TABLE SAMPLE (ID VARCHAR(50) PRIMARY KEY COMMENT '测试ID', TENANT VARCHAR(50) COMMENT '租户', CONTENT VARCHAR(100) COMMENT '正文') COMMENT 'Mapper事务隔离验证'");
        return ds;
    }
}
