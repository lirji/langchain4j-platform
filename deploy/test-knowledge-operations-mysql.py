#!/usr/bin/env python3
"""在 dev_infra 的隔离临时库验证知识运营持久化；分离迁移与 DML 账号，不触碰业务表。"""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import uuid


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="dev-infra-mysql84-1")
    parser.add_argument("--port", type=int, default=43306)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    suffix = uuid.uuid4().hex[:16]
    schema = "lc4j_ops_it_" + suffix
    app = "ops_app_" + suffix
    migrator = "ops_mig_" + suffix
    app_password = secrets.token_hex(24)
    migration_password = secrets.token_hex(24)
    command = ["docker", "exec", "-i", args.container, "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --skip-column-names -uroot']
    log = repository / "knowledge-service/target/knowledge-operations-mysql.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    # 所有标识和凭据都由本次进程生成；清理不接受外部数据库名称。
    provision = f"""
CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER '{app}'@'%' IDENTIFIED BY '{app_password}';
CREATE USER '{migrator}'@'%' IDENTIFIED BY '{migration_password}';
GRANT SELECT, INSERT, UPDATE ON `{schema}`.* TO '{app}'@'%';
GRANT ALL PRIVILEGES ON `{schema}`.* TO '{migrator}'@'%';
"""
    environment = os.environ.copy()
    environment.update(
        OPERATIONS_TEST_DB_URL=f"jdbc:mysql://127.0.0.1:{args.port}/{schema}?useSSL=false&allowPublicKeyRetrieval=true",
        OPERATIONS_TEST_DB_USER=app, OPERATIONS_TEST_DB_PASSWORD=app_password,
        OPERATIONS_TEST_MIGRATION_DB_USER=migrator, OPERATIONS_TEST_MIGRATION_DB_PASSWORD=migration_password,
    )
    try:
        subprocess.run(command, input=provision, text=True, check=True, capture_output=True)
        with log.open("w") as output:
            result = subprocess.run(
                ["mvn", "-o", "-B", "-pl", "knowledge-service", "-am",
                 "-Dtest=KnowledgeOperationsStoreTest,KnowledgeOperationsServiceTest,KnowledgeOperationsHttpTest,RetrievalQualityTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"],
                cwd=repository, env=environment, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            print(f"Knowledge operations MySQL verification: FAIL; log={log}")
            return result.returncode
        comments = subprocess.run(command, input=f"""
SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='{schema}'
AND TABLE_NAME IN ('KNOWLEDGE_OPERATION_FEEDBACK','KNOWLEDGE_OPERATION_CANDIDATE','KNOWLEDGE_OPERATION_AUDIT','KNOWLEDGE_OPERATION_RELEASE','KNOWLEDGE_QUALITY_DATASET','KNOWLEDGE_QUALITY_RUN','KNOWLEDGE_QUALITY_AUDIT') AND COLUMN_COMMENT='';
SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='{schema}'
AND TABLE_NAME IN ('KNOWLEDGE_OPERATION_FEEDBACK','KNOWLEDGE_OPERATION_CANDIDATE','KNOWLEDGE_OPERATION_AUDIT','KNOWLEDGE_OPERATION_RELEASE','KNOWLEDGE_QUALITY_DATASET','KNOWLEDGE_QUALITY_RUN','KNOWLEDGE_QUALITY_AUDIT') AND TABLE_COMMENT='';
""", text=True, check=True, capture_output=True)
        if comments.stdout.split() != ["0", "0"]:
            raise RuntimeError("MySQL table/column comments are incomplete")
        denial_env = {**os.environ, "MYSQL_PWD": app_password}
        denied = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD", args.container,
                                 "mysql", "-u" + app, schema],
                                input="CREATE TABLE OPERATIONS_DDL_MUST_BE_DENIED (ID INT COMMENT 'test column') COMMENT='DDL denial test';",
                                text=True, env=denial_env, capture_output=True)
        if denied.returncode == 0 or "1142" not in denied.stderr:
            raise RuntimeError("Application DDL restriction was not verified")
        print(json.dumps({"result": "PASS", "database": "isolated MySQL",
                          "schema_comments": "PASS", "application_ddl_denied": "PASS", "log": str(log)}))
        return 0
    finally:
        cleanup = f"DROP DATABASE IF EXISTS `{schema}`; DROP USER IF EXISTS '{app}'@'%'; DROP USER IF EXISTS '{migrator}'@'%';"
        subprocess.run(command, input=cleanup, text=True, check=True, capture_output=True)


if __name__ == "__main__":
    raise SystemExit(main())
