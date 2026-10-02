#!/usr/bin/env python3
"""在 dev_infra MySQL 的独立临时库验证只读worker调度；不接触现有业务表或输出凭据。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import uuid


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="dev-infra-mysql84-1")
    parser.add_argument("--port", type=int, default=43306)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    details = json.loads(subprocess.check_output(["docker", "inspect", args.container], text=True))[0]
    configuration = dict(item.split("=", 1) for item in details["Config"]["Env"] if "=" in item)
    credential = configuration.get("MYSQL_ROOT_PASSWORD")
    if not credential:
        raise SystemExit("MySQL root credential unavailable; no test database created")
    schema = "lc4j_dispatch_it_" + uuid.uuid4().hex
    sql_command = ["docker", "exec", "-i", args.container, "sh", "-c",
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot']
    # 名称完全由脚本生成，不接受调用者提供的库名；清理只针对本次创建的临时库。
    subprocess.run(sql_command, input=f"CREATE DATABASE {schema};", text=True, check=True, capture_output=True)
    environment = os.environ.copy()
    environment.update(DISPATCH_TEST_DB_URL=f"jdbc:mysql://127.0.0.1:{args.port}/{schema}?useSSL=false&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true",
                       DISPATCH_TEST_DB_USER="root", DISPATCH_TEST_DB_PASSWORD=credential)
    log = repository / ".git" / "codex-cross-runtime-reliability-baseline" / "dispatch-mysql.log"
    try:
        with log.open("w") as output:
            result = subprocess.run(["mvn", "-o", "-B", "-pl", "async-task-service", "-am",
                                     "-Dtest=ReadOnlyTaskDispatchTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"],
                                    cwd=repository, env=environment, stdout=output, stderr=subprocess.STDOUT)
        print(f"Isolated MySQL read-only dispatch verification: {'PASS' if result.returncode == 0 else 'FAIL'}; log={log}")
        return result.returncode
    finally:
        subprocess.run(sql_command, input=f"DROP DATABASE {schema};", text=True, check=True, capture_output=True)


if __name__ == "__main__":
    raise SystemExit(main())
