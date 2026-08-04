-- Local-development bootstrap only. Production databases, users and grants are
-- provisioned outside the application release and use External Secrets.
CREATE DATABASE IF NOT EXISTS auth CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS async_task CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS flowable CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS knowledge_graph CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS knowledge_ingestion CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS order_service CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS channel CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nl2sql_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'auth_app'@'%' IDENTIFIED BY 'auth-app-dev';
CREATE USER IF NOT EXISTS 'async_task_app'@'%' IDENTIFIED BY 'async-task-app-dev';
CREATE USER IF NOT EXISTS 'workflow_app'@'%' IDENTIFIED BY 'workflow-app-dev';
CREATE USER IF NOT EXISTS 'knowledge_graph_app'@'%' IDENTIFIED BY 'knowledge-graph-app-dev';
CREATE USER IF NOT EXISTS 'knowledge_ingestion_app'@'%' IDENTIFIED BY 'knowledge-ingestion-app-dev';
CREATE USER IF NOT EXISTS 'order_app'@'%' IDENTIFIED BY 'order-app-dev';
CREATE USER IF NOT EXISTS 'channel_app'@'%' IDENTIFIED BY 'channel-app-dev';
CREATE USER IF NOT EXISTS 'nl2sql_ro'@'%' IDENTIFIED BY 'nl2sql-readonly-dev';

GRANT SELECT, INSERT, UPDATE, DELETE ON auth.* TO 'auth_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON async_task.* TO 'async_task_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON flowable.* TO 'workflow_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON knowledge_graph.* TO 'knowledge_graph_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON knowledge_ingestion.* TO 'knowledge_ingestion_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON order_service.* TO 'order_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON channel.* TO 'channel_app'@'%';
GRANT SELECT ON nl2sql_demo.* TO 'nl2sql_ro'@'%';

CREATE USER IF NOT EXISTS 'auth_migrator'@'%' IDENTIFIED BY 'auth-migration-dev';
CREATE USER IF NOT EXISTS 'async_task_migrator'@'%' IDENTIFIED BY 'async-task-migration-dev';
CREATE USER IF NOT EXISTS 'workflow_migrator'@'%' IDENTIFIED BY 'workflow-migration-dev';
CREATE USER IF NOT EXISTS 'knowledge_graph_migrator'@'%' IDENTIFIED BY 'knowledge-graph-migration-dev';
CREATE USER IF NOT EXISTS 'knowledge_ingestion_migrator'@'%' IDENTIFIED BY 'knowledge-ingestion-migration-dev';
CREATE USER IF NOT EXISTS 'order_migrator'@'%' IDENTIFIED BY 'order-migration-dev';
CREATE USER IF NOT EXISTS 'channel_migrator'@'%' IDENTIFIED BY 'channel-migration-dev';
CREATE USER IF NOT EXISTS 'analytics_demo_migrator'@'%' IDENTIFIED BY 'analytics-demo-migration-dev';

GRANT ALL PRIVILEGES ON auth.* TO 'auth_migrator'@'%';
GRANT ALL PRIVILEGES ON async_task.* TO 'async_task_migrator'@'%';
GRANT ALL PRIVILEGES ON flowable.* TO 'workflow_migrator'@'%';
GRANT ALL PRIVILEGES ON knowledge_graph.* TO 'knowledge_graph_migrator'@'%';
GRANT ALL PRIVILEGES ON knowledge_ingestion.* TO 'knowledge_ingestion_migrator'@'%';
GRANT ALL PRIVILEGES ON order_service.* TO 'order_migrator'@'%';
GRANT ALL PRIVILEGES ON channel.* TO 'channel_migrator'@'%';
GRANT ALL PRIVILEGES ON nl2sql_demo.* TO 'analytics_demo_migrator'@'%';
