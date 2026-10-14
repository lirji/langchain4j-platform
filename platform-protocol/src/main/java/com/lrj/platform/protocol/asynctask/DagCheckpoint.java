package com.lrj.platform.protocol.asynctask;
import java.util.Map;
/** 语言中立检查点；revision=0/state=null 表示尚未保存，不携带运行时对象。 */
public record DagCheckpoint(long revision, Map<String, Object> state) { }
