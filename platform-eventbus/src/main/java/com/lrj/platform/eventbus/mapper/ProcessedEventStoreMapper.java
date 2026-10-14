package com.lrj.platform.eventbus.mapper;

import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 简单单表CRUD使用MP；启动契约检查保留在XML。 */
public interface ProcessedEventStoreMapper extends BaseMapper<ProcessedEventStoreRow> {
    /** 启动仅检查表，不执行DDL。 */
    List<ProcessedEventStoreRow> readInit1();
    /** 仅重领已释放的去重行；条件更新保证并发重投只有一个赢家。 */
    int reclaim(@org.apache.ibatis.annotations.Param("eventId") String eventId,
                @org.apache.ibatis.annotations.Param("processedAt") long processedAt);
}
