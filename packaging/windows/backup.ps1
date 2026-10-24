<#
.SYNOPSIS
  Sauvegarde complète de la base du hub : locale (14 jours), clé USB si présente.
  Le cloud détient par ailleurs une réplique continue via la synchronisation.
#>
param(
    [string]$InstallDir = "C:\PharmaCore",
    [string]$BackupDir = "C:\PharmaCore\backups",
    [int]$KeepDays = 14
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$file = Join-Path $BackupDir "pharmacore-$stamp.dump"
$env:PGPASSWORD = (Get-Content "$InstallDir\data\hub.env" | Where-Object { $_ -like "PHARMACORE_DB_OWNER_PASSWORD=*" }) -replace '^[^=]+=', ''
& "$InstallDir\pgsql\bin\pg_dump.exe" -p 5433 -U pharmacore_owner -d pharmacore -Fc -Z 6 -f $file
Remove-Item Env:PGPASSWORD

# Rotation locale
Get-ChildItem $BackupDir -Filter "pharmacore-*.dump" | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$KeepDays) } | Remove-Item -Force

# Copie sur toute clé USB contenant un dossier PharmaCoreBackup
Get-Volume | Where-Object { $_.DriveType -eq "Removable" -and $_.DriveLetter } | ForEach-Object {
    $target = "$($_.DriveLetter):\PharmaCoreBackup"
    if (Test-Path $target) {
        Copy-Item $file $target -Force
        Get-ChildItem $target -Filter "pharmacore-*.dump" | Sort-Object LastWriteTime -Descending | Select-Object -Skip 7 | Remove-Item -Force
    }
}
Write-Host "Sauvegarde écrite : $file"
