#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

workflow_files=(.github/workflows/*.yml .github/workflows/*.yaml)
existing_workflows=()
for workflow in "${workflow_files[@]}"; do
  [[ -f "$workflow" ]] && existing_workflows+=("$workflow")
done

if [[ "${#existing_workflows[@]}" -eq 0 ]]; then
  echo "no GitHub Actions workflows found" >&2
  exit 1
fi

unpinned_actions="$(rg -n '^\s*uses:' "${existing_workflows[@]}" | \
  rg -v '@[0-9a-f]{40}(\s+#.*)?$' || true)"
if [[ -n "$unpinned_actions" ]]; then
  echo "GitHub Actions must use immutable commit SHAs:" >&2
  echo "$unpinned_actions" >&2
  exit 1
fi

if rg -n '^\s*pull_request_target:' "${existing_workflows[@]}"; then
  echo "untrusted pull_request_target workflows are forbidden" >&2
  exit 1
fi

supply_chain=.github/workflows/supply-chain.yml
required_fragments=(
  'mvn -B -DskipITs test'
  'org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom'
  'target/langchain4j-platform.cdx.json'
  'aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25'
  'severity: HIGH,CRITICAL'
  'exit-code: "1"'
  "startsWith(github.ref, 'refs/tags/v')"
  'packages: write'
  'id-token: write'
  'attestations: write'
  'sbom: true'
  'provenance: mode=max'
  'cosign sign --yes "${IMAGE_NAME}@${IMAGE_DIGEST}"'
  'subject-digest: ${{ steps.image.outputs.digest }}'
  'sbom-path: release-evidence/${{ matrix.service }}.cdx.json'
)
for fragment in "${required_fragments[@]}"; do
  if ! rg -Fq -- "$fragment" "$supply_chain"; then
    echo "missing supply-chain control: $fragment" >&2
    exit 1
  fi
done

expected_images="$(find . -mindepth 2 -maxdepth 2 -name Dockerfile -not -path './deploy/*' | wc -l | tr -d ' ')"
matrix_images="$(sed -n '/^  image-scan:/,/^  release-images:/p' "$supply_chain" | rg -c '^          - [a-z0-9-]+$')"
if [[ "$expected_images" != "18" || "$matrix_images" != "$expected_images" ]]; then
  echo "image scan matrix must cover all 18 deployable Dockerfiles (found $matrix_images/$expected_images)" >&2
  exit 1
fi

if ! rg -q 'persist-credentials: false' "$supply_chain"; then
  echo "release workflow checkout must not persist GitHub credentials" >&2
  exit 1
fi

# 镜像 COPY 的 jar 名必须等于该模块真实产出的制品名。Spring Boot 的 repackage 默认让可执行
# fat jar 顶替普通 jar，只有配了 classifier 时两者才并存，此时可执行的那个带 -<classifier> 后缀。
# 不静态校验的话，pom 与 Dockerfile 的漂移要到发布阶段构建镜像时才暴露。
project_version="$(rg -o '<version>([^<]+)</version>' -r '$1' pom.xml | sed -n 2p)"
release_workflow=.github/workflows/supply-chain.yml
upload_globs="$(rg -o '^ +(\*/target/\S+\.jar)' -r '$1' "$release_workflow" | sort -u)"
if [[ -z "$upload_globs" ]]; then
  echo "no jar upload glob found in $release_workflow" >&2
  exit 1
fi
while IFS= read -r dockerfile; do
  module="$(dirname "$dockerfile")"
  [[ -f "$module/pom.xml" ]] || continue
  copied_jar="$(rg -o --no-filename 'target/[^ ]+\.jar' "$dockerfile" | head -1 || true)"
  [[ -n "$copied_jar" ]] || continue
  classifier="$(sed -n '/<artifactId>spring-boot-maven-plugin<\/artifactId>/,/<\/plugin>/p' "$module/pom.xml" \
    | rg -o '<classifier>([^<]+)</classifier>' -r '$1' | head -1 || true)"
  expected_jar="target/$(basename "$module")-${project_version}${classifier:+-${classifier}}.jar"
  if [[ "$copied_jar" != "$expected_jar" ]]; then
    echo "$dockerfile copies $copied_jar but the module builds $expected_jar" >&2
    exit 1
  fi

  # 镜像构建（image-scan / release-images）唯一的 jar 来源是 artifacts job 的 upload/download，
  # 所以上传通配符必须能匹配到这个 jar。带 classifier 的产物最容易漏（-exec.jar 不匹配 *-SNAPSHOT.jar）。
  # 用 read 而非 `for glob in $upload_globs`：后者会把通配符按本地文件名展开，校验就变成了
  # 「当前 target 目录里恰好有什么」而不是「CI 配置写了什么」。
  matched=false
  while IFS= read -r glob; do
    # shellcheck disable=SC2053 # 右侧就是要当通配符用
    if [[ "$(basename "$copied_jar")" == ${glob##*/} ]]; then
      matched=true
      break
    fi
  done <<<"$upload_globs"
  if [[ "$matched" != true ]]; then
    echo "$dockerfile needs $copied_jar but no upload glob in $release_workflow matches it" >&2
    exit 1
  fi
done < <(find . -mindepth 2 -maxdepth 2 -name Dockerfile -not -path './deploy/*')

echo "Java supply-chain config gate passed"
