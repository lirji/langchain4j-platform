#!/usr/bin/env python3
"""验证合成企业 PDF 的真实本地 OCR；不访问模型、数据库或业务容器。先执行 Maven package/test。"""
import argparse
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--image", default="lc4j-parser-check:kdp06c", help="本次验证使用的本地镜像标签")
    parser.add_argument("--evidence-dir", type=Path, help="独立证据目录，避免覆盖其他交付的验收结果")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    target = root / "knowledge-service/target"
    evidence = args.evidence_dir.resolve() if args.evidence_dir else target / "knowledge-parsing-hardening/kdp06c"
    evidence.mkdir(parents=True, exist_ok=True)
    report = ET.parse(target / "surefire-reports/TEST-com.lrj.platform.knowledge.lifecycle.TesseractOcrEngineTest.xml")
    classpath = report.find("./properties/property[@name='java.class.path']").get("value")
    fixtures = evidence / "fixtures"
    image = args.image

    def run(command, log, timeout):
        with (evidence / log).open("w") as output:
            subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=timeout)
        print(f"PASS {log}", flush=True)

    run(["java", "-cp", classpath, "com.lrj.platform.knowledge.lifecycle.OcrCorpusFixtures", str(fixtures)], "fixtures.log", 60)
    if not args.skip_build:
        # 不指定 target，验证标准默认镜像本身具备 OCR，而非只验证专用测试镜像。
        run(["docker", "build", "-t", image, "knowledge-service"], "docker-build.log", 900)
    restrictions = ["docker", "run", "--rm", "--network", "none", "--read-only", "--user", "10001:10001",
                    "--memory", "3g", "--cpus", "2", "--pids-limit", "256", "--tmpfs", "/tmp:size=512m,mode=1777"]
    run(restrictions + ["--entrypoint", "/usr/bin/tesseract", image, "--version"], "engine-version.log", 30)
    run(restrictions + ["--entrypoint", "/usr/bin/tesseract", image, "--list-langs"], "engine-languages.log", 30)
    run(restrictions + ["--entrypoint", "/usr/bin/dpkg-query", image, "-W", "tesseract-ocr*"], "packages.log", 30)
    run(restrictions + ["-v", f"{target / 'test-classes'}:/probe:ro", "-v", f"{fixtures}:/fixtures:ro",
                       "--entrypoint", "java", image,
                       "-Dloader.main=com.lrj.platform.knowledge.lifecycle.ParsingOcrContainerProbe",
                       "-Dloader.path=/probe", "-cp", "/app/app.jar",
                       "org.springframework.boot.loader.launch.PropertiesLauncher", "/fixtures"], "container-smoke.log", 300)


if __name__ == "__main__":
    main()
