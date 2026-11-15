$ErrorActionPreference = 'Stop'
$pidPath = Join-Path $PSScriptRoot '.runtime\relay.pid'
if (-not (Test-Path -LiteralPath $pidPath)) { Write-Output 'No recorded relay process.'; exit 0 }
$relayProcessId = [int](Get-Content -LiteralPath $pidPath)
$process = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $relayProcessId)
if (-not $process) { Write-Output 'Relay is already stopped.'; exit 0 }
$serverPath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'server.mjs'))
if ($process.Name -ne 'node.exe' -or
    -not $process.CommandLine -or
    $process.CommandLine.IndexOf($serverPath, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
    throw 'The recorded PID does not match this CarRadio relay. No process was stopped.'
}
Stop-Process -Id $relayProcessId
Write-Output 'CarRadio relay stopped.'
