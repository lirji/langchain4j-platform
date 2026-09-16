#!/usr/bin/env bash
# 统一指标面的静态门禁。
#
# 这里锁四件事的一致性：actuator 暴露清单里有 prometheus、Prometheus 导出器真的在 classpath 上、
# actuator 在独立 management 端口（业务端口 +1000）、以及默认拓扑里的服务都在抓取配置里且端口一致。
# 缺了任何一环都会出现「配置看起来有、实际抓不到」的死配置——本仓曾经就是 conversation/agent/vision
# 在 include 里写了 prometheus 但没有 micrometer-registry-prometheus 依赖。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

scrape_config=deploy/prometheus/prometheus.yml
alert_rules=deploy/prometheus/alerts.yml
helm_values=deploy/helm/platform/values.yaml

for required in "$scrape_config" "$alert_rules" "$helm_values"; do
  if [[ ! -f "$required" ]]; then
    echo "missing observability config: $required" >&2
    exit 1
  fi
done

# 常驻 Java 服务 = 有 Dockerfile 且 application.yml 里有 management 块（database-migrations 是一次性任务，
# capability-showcase-frontend 不是 Java，两者都没有 management 块，自然被排除）。
services=()
while IFS= read -r dockerfile; do
  module="$(basename "$(dirname "$dockerfile")")"
  yml="$module/src/main/resources/application.yml"
  [[ -f "$yml" ]] || continue
  rg -q '^management:' "$yml" || continue
  services+=("$module")
done < <(find . -mindepth 2 -maxdepth 2 -name Dockerfile -not -path './deploy/*' | sort)

if [[ "${#services[@]}" -lt 16 ]]; then
  echo "expected at least 16 long-running Java services, found ${#services[@]}" >&2
  exit 1
fi

# 只有默认 compose 拓扑里的服务该进抓取配置：profile 化的服务（legacy-agent / evaluation）默认不启动，
# 列进去只会制造长期 up==0 告警。
profiled_services="$(
  current=""
  while IFS= read -r line; do
    if [[ "$line" =~ ^\ \ ([a-z0-9-]+):$ ]]; then
      current="${BASH_REMATCH[1]}"
    elif [[ "$line" =~ ^\ \ \ \ profiles: ]]; then
      echo "$current"
    fi
  done < deploy/docker-compose.yml | sort -u
)"

for service in "${services[@]}"; do
  yml="$service/src/main/resources/application.yml"

  management_port="$(rg -o '^    port: \$\{MANAGEMENT_PORT:([0-9]+)\}' -r '$1' "$yml" | head -1 || true)"
  if [[ -z "$management_port" ]]; then
    echo "$service: actuator must listen on a dedicated management port (management.server.port=\${MANAGEMENT_PORT:...})" >&2
    exit 1
  fi

  include_line="$(rg -o '^        include: (\S+)' -r '$1' "$yml" | head -1 || true)"
  if [[ ",$include_line," != *",prometheus,"* ]]; then
    echo "$service: actuator exposure must include prometheus (found: ${include_line:-none})" >&2
    exit 1
  fi

  # 导出器可以直接声明，也可以经 platform-observability 传递（该模块用 runtime 且非 optional）
  if ! rg -q 'micrometer-registry-prometheus' "$service/pom.xml" \
     && ! rg -q 'platform-observability' "$service/pom.xml"; then
    echo "$service: /actuator/prometheus is exposed but micrometer-registry-prometheus is not on the classpath" >&2
    exit 1
  fi

  # 业务端口 +1000 的约定：Helm 模板与抓取配置都依赖它
  business_port="$(rg -o '^  port: ([0-9]+)' -r '$1' "$yml" | head -1 || true)"
  if [[ -n "$business_port" && "$management_port" != "$((business_port + 1000))" ]]; then
    echo "$service: management port $management_port must be business port $business_port + 1000" >&2
    exit 1
  fi

  if rg -qx "$service" <<<"$profiled_services"; then
    if rg -q "\"$service:" "$scrape_config"; then
      echo "$service: profile-gated service must not be a default scrape target" >&2
      exit 1
    fi
    continue
  fi

  if ! rg -q "\"$service:$management_port\"" "$scrape_config"; then
    echo "$service: missing scrape target \"$service:$management_port\" in $scrape_config" >&2
    exit 1
  fi

  # Helm 不给容器注入 SERVER_PORT / MANAGEMENT_PORT，容器端口、Service 端口和探针端口都是从
  # values.yaml 的 port 推出来的（mgmt 默认 = port + 1000）。所以 values 里的 port 必须等于服务
  # 自己 yml 里的 server.port，否则探针会打到没人监听的端口，Pod 永远 not ready。
  [[ -n "$business_port" ]] || continue
  helm_port="$(sed -n "/^  $service:$/,/^  [a-z]/p" "$helm_values" \
    | rg -o '^    port: ([0-9]+)' -r '$1' | head -1 || true)"
  if [[ -n "$helm_port" && "$helm_port" != "$business_port" ]]; then
    echo "$service: $helm_values port $helm_port disagrees with server.port $business_port" >&2
    exit 1
  fi
done

# AgentScope 编排运行时是跨语言指标面的另一半，必须一起被抓
if ! rg -q 'agentscope-orchestrator:8085' "$scrape_config"; then
  echo "missing agentscope-orchestrator scrape target in $scrape_config" >&2
  exit 1
fi

# 告警规则必须可判定：每条都要有 severity 与可执行的处置说明
alert_count="$(rg -c '^      - alert: ' "$alert_rules")"
for field in 'severity:' 'summary:' 'description:'; do
  field_count="$(rg -c "^ +$field" "$alert_rules")"
  if [[ "$field_count" -lt "$alert_count" ]]; then
    echo "every alert needs $field ($field_count for $alert_count alerts)" >&2
    exit 1
  fi
done

echo "observability config gate passed (${#services[@]} services, $alert_count alerts)"
