#!/usr/bin/env python3
"""用 dev_infra 随机 MySQL schema 和 S3 测试对象验证快照恢复；不迁移业务库。"""
import argparse
import os
from pathlib import Path
import secrets
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="dev-infra-mysql84-1")
    parser.add_argument("--mysql-port", type=int, default=43306)
    parser.add_argument("--s3-endpoint", default="http://127.0.0.1:49000")
    parser.add_argument("--s3-bucket", default="lc4j-knowledge-sources")
    parser.add_argument("--s3-access-key", default="lc4j-knowledge")
    parser.add_argument("--private-env", default="deploy/.dev-infra.env")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    # 只读取已有项目凭据，不创建/轮换共享账号；不回显配置或命令环境。
    values = dict(line.split("=", 1) for line in (root / args.private_env).read_text().splitlines()
                  if "=" in line and not line.startswith("#"))
    suffix = uuid.uuid4().hex[:16]
    schema, user, password = "lc4j_mp_it_" + suffix, "mp_it_" + suffix, secrets.token_hex(24)
    command = ["docker", "exec", "-i", args.container, "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch -uroot']
    environment = {**os.environ,
                   "MP_INGEST_TEST_DB_URL": f"jdbc:mysql://127.0.0.1:{args.mysql_port}/{schema}?useSSL=false&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true",
                   "MP_TEST_DB_USER": user, "MP_TEST_DB_PASSWORD": password,
                   "PREPARED_IT_S3_ENDPOINT": args.s3_endpoint, "PREPARED_IT_S3_BUCKET": args.s3_bucket,
                   "PREPARED_IT_S3_ACCESS_KEY": args.s3_access_key,
                   "PREPARED_IT_S3_SECRET_KEY": values["LC4J_S3_PASSWORD"]}
    log = root / "knowledge-service/target/knowledge-parsing-hardening/kdp04/mysql-s3.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    try:
        subprocess.run(command, input=f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON `{schema}`.* TO '{user}'@'%';",
                       text=True, check=True, capture_output=True)
        with log.open("w") as output:
            result = subprocess.run(["mvn", "-o", "-B", "-pl", "knowledge-service", "-am",
                                     "-Dtest=PreparedIngestionPersistenceTest,JdbcIngestionJobStoreTest",
                                     "-Dsurefire.failIfNoSpecifiedTests=false", "test"],
                                    cwd=root, env=environment, stdout=output, stderr=subprocess.STDOUT)
        print(f"MySQL/S3 snapshot recovery: {'PASS' if result.returncode == 0 else 'FAIL'}; log={log}")
        return result.returncode
    finally:
        # 随机标识只在当前进程生成；清理范围不接受外部 schema/account 参数。
        subprocess.run(command, input=f"DROP DATABASE IF EXISTS `{schema}`; DROP USER IF EXISTS '{user}'@'%';",
                       text=True, check=True, capture_output=True)


if __name__ == "__main__":
    raise SystemExit(main())
