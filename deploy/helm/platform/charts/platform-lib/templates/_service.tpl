{{/*
可复用 Service 模板。上下文 dict：{ name, svc, root }。
关键：Service 名称 == docker-compose 服务名，端口 == 容器端口，
使各服务现有跨服务 base-url env（如 http://knowledge-service:8084）在 k8s 内近零改动即可解析。
*/}}
{{- define "platform-lib.service" -}}
{{- $name := .name -}}
{{- $svc := .svc -}}
{{- $root := .root -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
  labels:
    {{- include "platform-lib.labels" (dict "name" $name "root" $root) | nindent 4 }}
spec:
  type: {{ (default (dict) $svc.service).type | default "ClusterIP" }}
  selector:
    {{- include "platform-lib.selectorLabels" (dict "name" $name "root" $root) | nindent 4 }}
  ports:
    - name: http
      port: {{ $svc.port }}
      targetPort: http
      protocol: TCP
      {{- if and (default (dict) $svc.service).nodePort }}
      nodePort: {{ $svc.service.nodePort }}
      {{- end }}
{{- end -}}
