param([string]$InstallDir = "C:\PharmaCore", [switch]$KeepData)
$ErrorActionPreference = "Continue"
foreach ($svc in @("PharmaCoreHub", "PharmaCorePostgres")) {
    if (Test-Path "$InstallDir\$svc.exe") { & "$InstallDir\$svc.exe" stop | Out-Null; & "$InstallDir\$svc.exe" uninstall | Out-Null }
}
Unregister-ScheduledTask -TaskName "PharmaCore Backup" -Confirm:$false -ErrorAction SilentlyContinue
Remove-NetFirewallRule -DisplayName "PharmaCore Hub" -ErrorAction SilentlyContinue
foreach ($dir in @("app", "runtime", "web", "pgsql")) { Remove-Item "$InstallDir\$dir" -Recurse -Force -ErrorAction SilentlyContinue }
if (-not $KeepData) { Write-Host "Les données sont conservées dans $InstallDir\data (supprimez-les manuellement si nécessaire)." }
