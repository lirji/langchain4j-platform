#!/usr/bin/env python3
"""在dev_infra的随机隔离schema补验认证、订单、图及入库Mapper；始终清理本次schema/账号。"""
import argparse
import os
from pathlib import Path
import secrets
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="dev-infra-mysql84-1")
    parser.add_argument("--port", type=int, default=43306)
    parser.add_argument("--test", choices=["MybatisAuthMySqlIT", "JdbcOrderStoreMySqlIT", "JdbcIngestionJobStoreTest", "JdbcGraphStoreTest"])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    command = ["docker", "exec", "-i", args.container, "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch -uroot']
    cases = [
        ("auth-service", "MybatisAuthMySqlIT", "MP_AUTH_TEST_DB_URL"),
        ("order-service", "JdbcOrderStoreMySqlIT", "ORDER_IT_MYSQL_URL"),
        ("knowledge-service", "JdbcIngestionJobStoreTest", "MP_INGEST_TEST_DB_URL"),
        ("knowledge-service", "JdbcGraphStoreTest", "MP_GRAPH_TEST_DB_URL"),
    ]
    for module, test, variable in cases:
        if args.test and args.test != test:
            continue
        suffix = uuid.uuid4().hex[:16]
        schema, user, password = "lc4j_mp_it_" + suffix, "mp_it_" + suffix, secrets.token_hex(24)
        environment = {**os.environ, variable: f"jdbc:mysql://127.0.0.1:{args.port}/{schema}?useSSL=false&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true",
                       "MP_TEST_DB_USER": user, "MP_TEST_DB_PASSWORD": password,
                       "ORDER_IT_MYSQL_USER": user, "ORDER_IT_MYSQL_PASSWORD": password}
        log = root / module / "target" / (test + "-mysql.log")
        log.parent.mkdir(parents=True, exist_ok=True)
        try:
            subprocess.run(command, input=f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON `{schema}`.* TO '{user}'@'%';",
                           text=True, check=True, capture_output=True)
            with log.open("w") as output:
                result = subprocess.run(["mvn", "-o", "-B", "-pl", module, "-am", "-Dtest=" + (test + ",LogicalDeleteStoresTest" if test == "MybatisAuthMySqlIT" else test),
                                         "-Dsurefire.failIfNoSpecifiedTests=false", "test"], cwd=root, env=environment,
                                        stdout=output, stderr=subprocess.STDOUT)
            print(f"{test}: {'PASS' if result.returncode == 0 else 'FAIL'}; log={log}", flush=True)
            if result.returncode:
                return result.returncode
        finally:
            subprocess.run(command, input=f"DROP DATABASE IF EXISTS `{schema}`; DROP USER IF EXISTS '{user}'@'%';",
                           text=True, check=True, capture_output=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
