{{/* Optional disruption budget for horizontally redundant workloads. */}}
{{- define "platform-lib.pdb" -}}
{{- $name := .name -}}
{{- $svc := .svc -}}
{{- $root := .root -}}
{{- $pdb := $svc.pdb -}}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ $name }}
  labels:
    {{- include "platform-lib.labels" (dict "name" $name "root" $root) | nindent 4 }}
spec:
  {{- if hasKey $pdb "minAvailable" }}
  minAvailable: {{ $pdb.minAvailable }}
  {{- else }}
  maxUnavailable: {{ $pdb.maxUnavailable | default 1 }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "platform-lib.selectorLabels" (dict "name" $name "root" $root) | nindent 6 }}
{{- end -}}
