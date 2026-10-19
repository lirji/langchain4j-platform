-- 先扩展再升级 worker；NULL 表示旧任务，不能据此假设没有产生过索引副作用。
ALTER TABLE KNOWLEDGE_INGESTION_JOB ADD COLUMN PREPARATION_JSON TEXT NULL COMMENT '准备协议版本、生成版本和不可变结果对象引用；NULL为旧任务，正文和向量保存在对象存储';
