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
{{- $probes := $g.probes -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  labels:
    {{- include "platform-lib.labels" (dict "name" $name "root" $root) | nindent 4 }}
spec:
  replicas: {{ $svc.replicaCount | default 1 }}
  selector:
    matchLabels:
      {{- include "platform-lib.selectorLabels" (dict "name" $name "root" $root) | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "platform-lib.selectorLabels" (dict "name" $name "root" $root) | nindent 8 }}
    spec:
      {{- with $g.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: {{ $name }}
          {{- $img := default (dict) $svc.image }}
          image: "{{ $img.repository | default (printf "%s/%s" $g.image.registry $name) }}:{{ $img.tag | default $g.image.tag }}"
          imagePullPolicy: {{ $img.pullPolicy | default $g.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ $svc.port }}
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
              port: http
            initialDelaySeconds: {{ $probes.liveness.initialDelaySeconds }}
            periodSeconds: {{ $probes.liveness.periodSeconds }}
            timeoutSeconds: {{ $probes.liveness.timeoutSeconds }}
            failureThreshold: {{ $probes.liveness.failureThreshold }}
          readinessProbe:
            httpGet:
              path: {{ $probes.readinessPath }}
              port: http
            initialDelaySeconds: {{ $probes.readiness.initialDelaySeconds }}
            periodSeconds: {{ $probes.readiness.periodSeconds }}
            timeoutSeconds: {{ $probes.readiness.timeoutSeconds }}
            failureThreshold: {{ $probes.readiness.failureThreshold }}
          {{- end }}
          resources:
            {{- toYaml ($svc.resources | default $g.resources) | nindent 12 }}
          {{- with $svc.volumeMounts }}
          volumeMounts:
            {{- toYaml . | nindent 12 }}
          {{- end }}
      {{- with $svc.volumes }}
      volumes:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}
