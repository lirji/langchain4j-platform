{{/*
共享 helper：标签、selector、env / envFrom 渲染。
所有模板都以 dict 形式接收上下文：{ "name": <服务名>, "svc": <该服务 values 片段>, "root": <$ 根> }。
*/}}

{{/* 通用标签（含 Helm 标准 + 服务名） */}}
{{- define "platform-lib.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: langchain4j-platform
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name (.root.Chart.Version | replace "+" "_") }}
{{- end -}}

{{/* selector 标签（稳定，不含 chart/version，避免升级时改动 selector） */}}
{{- define "platform-lib.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/*
渲染 env 列表。每个条目支持三种形态：
  - { name: FOO, value: "bar" }                     # 明文
  - { name: FOO, secret: <key>, secretName: <n> }   # 引用 Secret（默认 platform-secrets）
  - { name: FOO, config: <key>, configName: <n> }   # 引用 ConfigMap（默认 platform-config）
调用：include "platform-lib.renderEnv" (dict "items" $list "root" $root)
*/}}
{{- define "platform-lib.renderEnv" -}}
{{- range .items }}
- name: {{ .name }}
{{- if hasKey . "value" }}
  value: {{ .value | quote }}
{{- else if hasKey . "secret" }}
  valueFrom:
    secretKeyRef:
      name: {{ .secretName | default "platform-secrets" }}
      key: {{ .secret }}
{{- else if hasKey . "config" }}
  valueFrom:
    configMapKeyRef:
      name: {{ .configName | default "platform-config" }}
      key: {{ .config }}
{{- end }}
{{- end }}
{{- end -}}

{{/*
渲染 envFrom 列表。每个条目：{ configMap: <name> } 或 { secret: <name> }。
调用：include "platform-lib.renderEnvFrom" (dict "items" $list)
*/}}
{{- define "platform-lib.renderEnvFrom" -}}
{{- range .items }}
{{- if .configMap }}
- configMapRef:
    name: {{ .configMap }}
{{- else if .secret }}
- secretRef:
    name: {{ .secret }}
{{- end }}
{{- end }}
{{- end -}}
