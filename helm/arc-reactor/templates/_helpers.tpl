{{/*
Expand the name of the chart.
*/}}
{{- define "arc-reactor.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "arc-reactor.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "arc-reactor.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "arc-reactor.labels" -}}
helm.sh/chart: {{ include "arc-reactor.chart" . }}
{{ include "arc-reactor.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "arc-reactor.selectorLabels" -}}
app.kubernetes.io/name: {{ include "arc-reactor.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "arc-reactor.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "arc-reactor.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Name of the secret containing API keys and credentials.
If existingSecret is set, use that; otherwise use the chart-managed secret name.
*/}}
{{- define "arc-reactor.secretName" -}}
{{- if .Values.existingSecret }}
{{- .Values.existingSecret }}
{{- else }}
{{- include "arc-reactor.fullname" . }}
{{- end }}
{{- end }}

{{/*
Resolve the container image tag.
Falls back to Chart.AppVersion when values.image.tag is empty.
*/}}
{{- define "arc-reactor.imageTag" -}}
{{- if .Values.image.tag }}
{{- .Values.image.tag }}
{{- else }}
{{- .Chart.AppVersion }}
{{- end }}
{{- end }}

{{/*
Build the SPRING_DATASOURCE_URL from postgresql values.
Rendered only when postgresql.host is non-empty.
*/}}
{{- define "arc-reactor.datasourceUrl" -}}
{{- if .Values.postgresql.host -}}
jdbc:postgresql://{{ .Values.postgresql.host }}:{{ .Values.postgresql.port }}/{{ .Values.postgresql.database }}
{{- else if .Values.postgresql.enabled -}}
jdbc:postgresql://{{ include "arc-reactor.fullname" . }}-postgresql:{{ .Values.postgresql.port }}/{{ .Values.postgresql.database }}
{{- end -}}
{{- end }}

{{/*
Name of the bundled PostgreSQL StatefulSet / Service (used when postgresql.enabled=true).
*/}}
{{- define "arc-reactor.postgresqlName" -}}
{{- printf "%s-postgresql" (include "arc-reactor.fullname" .) }}
{{- end }}
