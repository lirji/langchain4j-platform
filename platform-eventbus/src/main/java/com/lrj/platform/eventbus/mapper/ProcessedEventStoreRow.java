package com.lrj.platform.eventbus.mapper;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;

/** 事件去重记录以外部事件ID为主键，不能由ORM生成新的ID破坏幂等。 */
@TableName("PROCESSED_EVENT")
public class ProcessedEventStoreRow {
    @TableId(value = "EVENT_ID", type = IdType.INPUT)
    public String eventId;
    /** 处理时间采用既有UTC epoch毫秒，不改存储格式。 */
    public Long processedAt;
}
