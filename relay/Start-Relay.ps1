$ErrorActionPreference = 'Stop'
$nodePath = (Get-Command node -ErrorAction Stop).Source
$serverPath = Join-Path $PSScriptRoot 'server.mjs'
$statePath = Join-Path $PSScriptRoot '.runtime'
New-Item -ItemType Directory -Path $statePath -Force | Out-Null
$health = $null
try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/health' -TimeoutSec 1
} catch {
    if ($_.Exception.Response) { throw 'Port 8765 is in use by another service.' }
}
if ($health) {
    if ($health.service -eq 'CarRadio local relay') {
        if (-not $health.features -or $health.features.walkieTalkie -ne 1 -or $health.features.walkieControls -ne 1) {
            throw 'An older CarRadio relay is running. Run Stop-Relay.ps1, then Start-Relay.ps1.'
        }
        Write-Output 'CarRadio relay is already running.'
        exit 0
    }
    throw 'Port 8765 is already in use by another service.'
}
$process = Start-Process -FilePath $nodePath -ArgumentList @('"' + $serverPath + '"') `
    -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $statePath 'relay.out.log') `
    -RedirectStandardError (Join-Path $statePath 'relay.err.log')
Set-Content -LiteralPath (Join-Path $statePath 'relay.pid') -Value $process.Id
Write-Output ('CarRadio relay starting. PID: ' + $process.Id)
