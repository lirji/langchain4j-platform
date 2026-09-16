{{/*
可复用 Deployment 模板。上下文 dict：{ name, svc, root }。
镜像/端口/副本/env/探针/资源全部来自 values；探针复用 Spring Boot actuator
health group（/actuator/health/liveness、/actuator/health/readiness）。
*/}}
{{- define "platform-lib.deployment" -}}
{{- $name := .name -}}
{{- $svc := .svc -}}
{{- $root := .root -}}
{{- $g := $root.Values.global -}}
{{- $probes := $svc.probes | default $g.probes -}}
{{- /*
  actuator / 指标端口：默认业务端口 +1000，与各服务 application.yml 里 MANAGEMENT_PORT 的默认值一致。
  Spring 的 management 子上下文不继承业务端口上的租户鉴权 filter，因此探针与 Prometheus 无需凭据；
  该端口只进 Service 的集群内端口，不对外发布。非 Spring 服务（AgentScope）置 managementPort: 0，
  表示健康与 /metrics 就在业务端口上。
*/ -}}
{{- /* 用 hasKey 而不是 default：sprig 的 default 把显式的 0 当空值，会让 managementPort: 0 失效。 */ -}}
{{- $mgmtPort := int (ternary $svc.managementPort (add $svc.port 1000) (hasKey $svc "managementPort")) -}}
{{- $probePort := $probes.port | default (ternary "mgmt" "http" (gt $mgmtPort 0)) -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  labels:
    {{- include "platform-lib.labels" (dict "name" $name "root" $root) | nindent 4 }}
spec:
  revisionHistoryLimit: {{ $g.revisionHistoryLimit | default 3 }}
  progressDeadlineSeconds: {{ $g.progressDeadlineSeconds | default 600 }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: {{ $g.rollingUpdate.maxUnavailable | default 0 }}
      maxSurge: {{ $g.rollingUpdate.maxSurge | default 1 }}
  replicas: {{ $svc.replicaCount | default 1 }}
  selector:
    matchLabels:
      {{- include "platform-lib.selectorLabels" (dict "name" $name "root" $root) | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "platform-lib.selectorLabels" (dict "name" $name "root" $root) | nindent 8 }}
    spec:
      serviceAccountName: {{ $svc.serviceAccountName | default $name }}
      automountServiceAccountToken: false
      enableServiceLinks: false
      securityContext:
        {{- toYaml ($svc.podSecurityContext | default $g.podSecurityContext) | nindent 8 }}
      {{- $topology := $svc.topologySpread | default $g.topologySpread }}
      {{- if $topology.enabled }}
      topologySpreadConstraints:
        {{- range $topology.constraints }}
        - maxSkew: {{ .maxSkew }}
          topologyKey: {{ .topologyKey }}
          whenUnsatisfiable: {{ .whenUnsatisfiable }}
          labelSelector:
            matchLabels:
              {{- include "platform-lib.selectorLabels" (dict "name" $name "root" $root) | nindent 14 }}
        {{- end }}
      {{- end }}
      {{- with $svc.terminationGracePeriodSeconds }}
      terminationGracePeriodSeconds: {{ . }}
      {{- end }}
      {{- with $g.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: {{ $name }}
          {{- $img := default (dict) $svc.image }}
          image: "{{ $img.repository | default (printf "%s/%s" $g.image.registry $name) }}:{{ $img.tag | default $g.image.tag }}"
          imagePullPolicy: {{ $img.pullPolicy | default $g.image.pullPolicy }}
          securityContext:
            {{- toYaml ($svc.securityContext | default $g.securityContext) | nindent 12 }}
          ports:
            - name: http
              containerPort: {{ $svc.port }}
            {{- if gt $mgmtPort 0 }}
            - name: mgmt
              containerPort: {{ $mgmtPort }}
            {{- end }}
          {{- $envFrom := concat ($g.envFrom | default list) ($svc.envFrom | default list) }}
          {{- if $envFrom }}
          envFrom:
            {{- include "platform-lib.renderEnvFrom" (dict "items" $envFrom) | nindent 12 }}
          {{- end }}
          {{- $env := concat ($g.sharedEnv | default list) ($svc.env | default list) }}
          {{- if $env }}
          env:
            {{- include "platform-lib.renderEnv" (dict "items" $env "root" $root) | nindent 12 }}
          {{- end }}
          {{- if $probes.enabled }}
          livenessProbe:
            httpGet:
              path: {{ $probes.livenessPath }}
              port: {{ $probePort }}
            initialDelaySeconds: {{ $probes.liveness.initialDelaySeconds }}
            periodSeconds: {{ $probes.liveness.periodSeconds }}
            timeoutSeconds: {{ $probes.liveness.timeoutSeconds }}
            failureThreshold: {{ $probes.liveness.failureThreshold }}
          readinessProbe:
            httpGet:
              path: {{ $probes.readinessPath }}
              port: {{ $probePort }}
            initialDelaySeconds: {{ $probes.readiness.initialDelaySeconds }}
            periodSeconds: {{ $probes.readiness.periodSeconds }}
            timeoutSeconds: {{ $probes.readiness.timeoutSeconds }}
            failureThreshold: {{ $probes.readiness.failureThreshold }}
          {{- end }}
          resources:
            {{- toYaml ($svc.resources | default $g.resources) | nindent 12 }}
          volumeMounts:
            - name: runtime-tmp
              mountPath: /tmp
          {{- with $svc.volumeMounts }}
            {{- toYaml . | nindent 12 }}
          {{- end }}
      volumes:
        - name: runtime-tmp
          emptyDir:
            sizeLimit: {{ $g.runtimeTmpSizeLimit | default "256Mi" }}
      {{- with $svc.volumes }}
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}
