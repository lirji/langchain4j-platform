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
if [[ "$expected_images" != "17" || "$matrix_images" != "$expected_images" ]]; then
  echo "image scan matrix must cover all 17 deployable Dockerfiles (found $matrix_images/$expected_images)" >&2
  exit 1
fi

if ! rg -q 'persist-credentials: false' "$supply_chain"; then
  echo "release workflow checkout must not persist GitHub credentials" >&2
  exit 1
fi

echo "Java supply-chain config gate passed"
