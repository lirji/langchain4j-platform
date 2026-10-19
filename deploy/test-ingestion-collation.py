#!/usr/bin/env python3
"""在 dev_infra 随机 MySQL 库复现/验证两种历史 collation 方向，不改业务库。"""
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
    parser.add_argument("--evidence-dir", default="database-migrations/target/ingestion-collation")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    evidence = root / args.evidence_dir
    evidence.mkdir(parents=True, exist_ok=True)
    mysql = ["docker", "exec", "-i", args.container, "sh", "-c",
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch -uroot']
    failures = 0
    for collation in ("utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci"):
        suffix = uuid.uuid4().hex[:16]
        schema, user, password = "lc4j_collation_it_" + suffix, "coll_it_" + suffix, secrets.token_hex(24)
        env = {**os.environ, "COLLATION_TEST_DB_URL": f"jdbc:mysql://127.0.0.1:{args.mysql_port}/{schema}?useSSL=false&allowPublicKeyRetrieval=true",
               "COLLATION_TEST_DB_USER": user, "COLLATION_TEST_DB_PASSWORD": password,
               "COLLATION_TEST_JOB_COLLATION": collation}
        try:
            result = subprocess.run(mysql, input=f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON `{schema}`.* TO '{user}'@'%';",
                                    text=True, capture_output=True)
            if result.returncode:
                raise RuntimeError("无法准备隔离 MySQL 库；未回显凭据")
            with (evidence / f"{collation}.log").open("w") as output:
                result = subprocess.run(["mvn", "-B", "-o", "-pl", "database-migrations",
                                         "-Dtest=KnowledgeTenantCollationMigrationTest", "test"],
                                        cwd=root, env=env, stdout=output, stderr=subprocess.STDOUT)
            failures += result.returncode != 0
            print(f"{collation}: {'PASS' if result.returncode == 0 else 'FAIL'}", flush=True)
        finally:
            # 标识由本进程生成，清理仅限本轮测试创建的 schema/账号。
            result = subprocess.run(mysql, input=f"DROP DATABASE IF EXISTS `{schema}`; DROP USER IF EXISTS '{user}'@'%';",
                                    text=True, capture_output=True)
            if result.returncode:
                raise RuntimeError("隔离测试资源清理失败，请检查本轮随机资源；未回显凭据")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
