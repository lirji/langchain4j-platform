package com.lrj.platform.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;

/** 显式绑定既有数据源，避免多库/Flowable场景被全局自动装配或Mapper扫描抢占。 */
public final class PlusMapperFactory {
    private PlusMapperFactory() {}

    /** 会话由Spring接管事务；不缓存业务查询，锁行/跨Mapper读必须看到当前事务的最新数据。 */
    public static <T> T create(DataSource source, Class<T> mapperType) {
        return create(source, mapperType, null);
    }

    /** 仅沿用原存储显式配置的SQL超时；未配置时仍服从事务/驱动，避免重构改变慢查询行为。 */
    public static <T> T create(DataSource source, Class<T> mapperType, Integer statementTimeoutSeconds) {
        try {
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            // 投影列全部为NULL仍代表数据库有这一行，保持原RowMapper的调用语义。
            configuration.setReturnInstanceForEmptyRow(true);
            configuration.setCacheEnabled(false);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setJdbcTypeForNull(JdbcType.NULL);
            configuration.setDefaultStatementTimeout(statementTimeoutSeconds);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setTransactionFactory(new SpringManagedTransactionFactory());
            factory.setGlobalConfig(new GlobalConfig().setBanner(false));
            var xml = new ClassPathResource(mapperType.getName().replace('.', '/') + ".xml");
            if (xml.exists()) factory.setMapperLocations(xml);
            var sessionFactory = factory.getObject();
            if (!sessionFactory.getConfiguration().hasMapper(mapperType)) sessionFactory.getConfiguration().addMapper(mapperType);
            return new SqlSessionTemplate(sessionFactory).getMapper(mapperType);
        } catch (Exception e) {
            throw new IllegalStateException("MyBatis-Plus mapper initialization failed: " + mapperType.getName(), e);
        }
    }
}
