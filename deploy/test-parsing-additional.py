#!/usr/bin/env python3
"""使用真实 Boot jar 和受限 Linux 验证其他文档格式；先运行 Maven package/test，不访问业务数据。"""
import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    target = root / "knowledge-service/target"
    evidence = target / "knowledge-parsing-hardening/kdp06e"
    evidence.mkdir(parents=True, exist_ok=True)
    image = "lc4j-parser-check:kdp06e"

    def run(command, log, timeout):
        with (evidence / log).open("w") as output:
            subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=timeout)
        print(f"PASS {log}", flush=True)

    if not args.skip_build:
        run(["docker", "build", "--target", "runtime", "-t", image, "knowledge-service"], "docker-build.log", 600)
    run(["docker", "run", "--rm", "--network", "none", "--read-only", "--user", "10001:10001",
         "--memory", "3g", "--cpus", "2", "--pids-limit", "256", "--tmpfs", "/tmp:size=512m,mode=1777",
         "-v", f"{target / 'test-classes'}:/probe:ro", "--entrypoint", "java", image,
         "-Dloader.main=com.lrj.platform.knowledge.lifecycle.ParsingAdditionalContainerProbe",
         "-Dloader.path=/probe", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"],
        "container-smoke.log", 300)


if __name__ == "__main__":
    main()
