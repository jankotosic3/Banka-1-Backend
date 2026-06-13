<#
.SYNOPSIS
  Okida ServiceProbeSlow tako što ubaci +1500ms latencije na health probu credit-service-a
  preko toxiproxy-ja i privremeno preusmeri Prometheus probe kroz proxy.

.EXAMPLE
  ./setup/probe-slow-demo.ps1 on    # ubaci latenciju -> za ~30s stigne ⚠️ ServiceProbeSlow
  ./setup/probe-slow-demo.ps1 off   # ukloni latenciju, vrati direktnu probu
#>
param([Parameter(Mandatory)][ValidateSet('on','off')]$Mode)
$ErrorActionPreference = 'Stop'
$dir    = $PSScriptRoot
$promyml = Join-Path $dir 'prometheus.yml'
$tox    = 'http://localhost:8474'
$direct = 'http://banka_credit_service:8089/health'
$viaTox = 'http://toxiproxy:8666/health'
# toxiproxy admin API odbija "browser" User-Agent (PowerShell default sadrzi Mozilla) -> postavi ne-browser UA.
$ua = 'toxiproxy-client'

function Save([string]$path,[string]$content){ [IO.File]::WriteAllText($path,$content,(New-Object Text.UTF8Encoding($false))) }

if ($Mode -eq 'on') {
  try { Invoke-RestMethod -Method Delete "$tox/proxies/credit-slow" -UserAgent $ua -TimeoutSec 5 | Out-Null } catch {}
  Invoke-RestMethod -Method Post "$tox/proxies" -UserAgent $ua -ContentType application/json -Body '{"name":"credit-slow","listen":"0.0.0.0:8666","upstream":"banka_credit_service:8089"}' | Out-Null
  Invoke-RestMethod -Method Post "$tox/proxies/credit-slow/toxics" -UserAgent $ua -ContentType application/json -Body '{"type":"latency","attributes":{"latency":1500}}' | Out-Null
  Save $promyml ((Get-Content $promyml -Raw).Replace($direct, $viaTox))
  Write-Host "ProbeSlow ON: credit-service probe ide kroz toxiproxy (+1500ms)." -ForegroundColor Yellow
} else {
  try { Invoke-RestMethod -Method Delete "$tox/proxies/credit-slow" -UserAgent $ua -TimeoutSec 5 | Out-Null } catch {}
  Save $promyml ((Get-Content $promyml -Raw).Replace($viaTox, $direct))
  Write-Host "ProbeSlow OFF: vraćena direktna proba." -ForegroundColor Green
}
docker compose -f (Join-Path $dir 'docker-compose.yml') restart prometheus | Out-Null
for ($i=0; $i -lt 12; $i++) { Start-Sleep -Seconds 2; try { $null = Invoke-RestMethod 'http://localhost:9090/-/ready' -TimeoutSec 3; break } catch {} }
Write-Host "Prometheus reload-ovan."
