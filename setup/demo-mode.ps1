<#
.SYNOPSIS
  Privremeni DEMO režim Prometheus pravila (kratki `for`, spušteni pragovi) da warning
  alarmi okidaju brzo za testiranje/snimak. Vraća na produkciju sa `off`.

  prometheus-rules.yml je JEDINI standardni fajl. `on` napravi privremeni
  prometheus-rules.yml.bak (kopija produkcije) i ubaci demo; `off` vrati .bak i obriše ga.

.EXAMPLE
  ./setup/demo-mode.ps1 on       # aktiviraj demo pragove + reload Prometheus
  ./setup/demo-mode.ps1 off      # vrati produkcione pragove
  ./setup/demo-mode.ps1 status   # koja je verzija trenutno aktivna
#>
param(
  [Parameter(Mandatory)][ValidateSet('on','off','status')]$Mode
)
$ErrorActionPreference = 'Stop'
$dir    = $PSScriptRoot
$active = Join-Path $dir 'prometheus-rules.yml'
$demo   = Join-Path $dir 'prometheus-rules.demo.yml'
$bak    = Join-Path $dir 'prometheus-rules.yml.bak'

function IsDemo { (Get-Content $active -Raw) -like '*DEMO MODE*' }

switch ($Mode) {
  'status' {
    if (IsDemo) { Write-Host 'Aktivno: DEMO' -ForegroundColor Yellow }
    else        { Write-Host 'Aktivno: PRODUKCIJA (prometheus-rules.yml)' -ForegroundColor Green }
    return
  }
  'on' {
    if (IsDemo) { Write-Host 'Vec je DEMO režim.' -ForegroundColor Yellow; return }
    if (-not (Test-Path $demo)) { throw "Nedostaje $demo" }
    Copy-Item $active $bak -Force        # privremena kopija produkcije
    Copy-Item $demo $active -Force
  }
  'off' {
    if (Test-Path $bak) {
      Copy-Item $bak $active -Force
      Remove-Item $bak -Force
    } elseif (-not (IsDemo)) {
      Write-Host 'Vec je PRODUKCIJA.' -ForegroundColor Green; return
    } else {
      Write-Host 'Demo je aktivan ali nema .bak — vrati rucno iz git-a (git checkout setup/prometheus-rules.yml).' -ForegroundColor Red; return
    }
  }
}
docker compose -f (Join-Path $dir 'docker-compose.yml') restart prometheus | Out-Null
for ($i=0; $i -lt 12; $i++) { Start-Sleep -Seconds 2; try { $null = Invoke-RestMethod 'http://localhost:9090/-/ready' -TimeoutSec 3; break } catch {} }
Write-Host ("Demo režim: {0}  (Prometheus reload-ovan)" -f $Mode.ToUpper()) -ForegroundColor Cyan
