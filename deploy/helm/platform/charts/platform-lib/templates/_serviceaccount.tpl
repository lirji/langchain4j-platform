{{/* Dedicated, tokenless ServiceAccount for one workload. No RoleBinding is created. */}}
{{- define "platform-lib.serviceAccount" -}}
{{- $name := .name -}}
{{- $root := .root -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ $name }}
  labels:
    {{- include "platform-lib.labels" (dict "name" $name "root" $root) | nindent 4 }}
automountServiceAccountToken: false
{{- end -}}
