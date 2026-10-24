<#
.SYNOPSIS
  Restaure le hub depuis une sauvegarde locale (.dump). Arrête le hub, remplace la base, redémarre.
  Après restauration, le hub se resynchronise avec le cloud (les changements postérieurs à la
  sauvegarde et déjà poussés au cloud sont récupérés par un rechargement complet si nécessaire).
#>
param(
    [Parameter(Mandatory = $true)][string]$DumpFile,
    [string]$InstallDir = "C:\PharmaCore"
)
$ErrorActionPreference = "Stop"
& "$InstallDir\PharmaCoreHub.exe" stop | Out-Null
$env:PGPASSWORD = (Get-Content "$InstallDir\data\hub.env" | Where-Object { $_ -like "PHARMACORE_DB_OWNER_PASSWORD=*" }) -replace '^[^=]+=', ''
& "$InstallDir\pgsql\bin\psql.exe" -p 5433 -U pharmacore_owner -d postgres -c "DROP DATABASE IF EXISTS pharmacore_restore;"
& "$InstallDir\pgsql\bin\psql.exe" -p 5433 -U pharmacore_owner -d postgres -c "CREATE DATABASE pharmacore_restore OWNER pharmacore_owner;"
& "$InstallDir\pgsql\bin\pg_restore.exe" -p 5433 -U pharmacore_owner -d pharmacore_restore --no-owner --role=pharmacore_owner $DumpFile
& "$InstallDir\pgsql\bin\psql.exe" -p 5433 -U pharmacore_owner -d postgres -c "ALTER DATABASE pharmacore RENAME TO pharmacore_old_$(Get-Date -Format 'yyyyMMddHHmm');"
& "$InstallDir\pgsql\bin\psql.exe" -p 5433 -U pharmacore_owner -d postgres -c "ALTER DATABASE pharmacore_restore RENAME TO pharmacore;"
Remove-Item Env:PGPASSWORD
& "$InstallDir\PharmaCoreHub.exe" start | Out-Null
Write-Host "Base restaurée depuis $DumpFile ; l'ancienne base est conservée sous pharmacore_old_*."
