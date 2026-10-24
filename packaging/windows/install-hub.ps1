<#
.SYNOPSIS
  Installe le hub PharmaCore sur un poste Windows (à lancer en administrateur).

.DESCRIPTION
  - initialise PostgreSQL (dossier data, rôles de production, mots de passe générés)
  - enregistre deux services Windows (PostgreSQL puis le hub) avec redémarrage automatique
  - ouvre le port 8080 sur le réseau privé de l'officine uniquement
  - planifie la sauvegarde quotidienne (locale + clé USB si présente)
  Idempotent : relançable après une mise à jour.

.PARAMETER CloudUrl
  URL du cloud PharmaCore (ex. https://cloud.pharmacore.io)
#>
param(
    [string]$InstallDir = "C:\PharmaCore",
    [string]$CloudUrl = "https://cloud.pharmacore.io",
    [string]$BackupDir = "C:\PharmaCore\backups"
)

$ErrorActionPreference = "Stop"
$source = Split-Path -Parent $MyInvocation.MyCommand.Path

function New-Secret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ([Convert]::ToBase64String($bytes) -replace '[^a-zA-Z0-9]', '').Substring(0, 24)
}

Write-Host "== Installation de PharmaCore Hub dans $InstallDir"
New-Item -ItemType Directory -Force -Path $InstallDir, "$InstallDir\data", "$InstallDir\data\keys", "$InstallDir\data\logs", $BackupDir | Out-Null

# Fichiers de l'application (jar, runtime Java, PWA, PostgreSQL, WinSW)
foreach ($dir in @("app", "runtime", "web", "pgsql")) {
    if (Test-Path "$source\$dir") {
        robocopy "$source\$dir" "$InstallDir\$dir" /MIR /NFL /NDL /NJH /NJS | Out-Null
    }
}
Copy-Item "$source\winsw.exe" "$InstallDir\PharmaCoreHub.exe" -Force
Copy-Item "$source\winsw.exe" "$InstallDir\PharmaCorePostgres.exe" -Force
Copy-Item "$source\pharmacore-hub.xml" "$InstallDir\PharmaCoreHub.xml" -Force
Copy-Item "$source\pharmacore-postgres.xml" "$InstallDir\PharmaCorePostgres.xml" -Force
Copy-Item "$source\backup.ps1" "$InstallDir\backup.ps1" -Force

# Secrets : générés une fois, conservés dans data\hub.env (ACL administrateurs + SYSTEM)
$envFile = "$InstallDir\data\hub.env"
if (-not (Test-Path $envFile)) {
    @(
        "PHARMACORE_DB_OWNER_PASSWORD=$(New-Secret)",
        "PHARMACORE_DB_APP_PASSWORD=$(New-Secret)",
        "PHARMACORE_CLOUD_URL=$CloudUrl"
    ) | Set-Content -Path $envFile -Encoding ASCII
    $acl = Get-Acl $envFile
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($identity in @("BUILTIN\Administrators", "NT AUTHORITY\SYSTEM")) {
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($identity, "FullControl", "Allow")
        $acl.AddAccessRule($rule)
    }
    Set-Acl $envFile $acl
}
Get-Content $envFile | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, "Machine")
    Set-Item -Path "Env:$name" -Value $value
}
# Même protection pour la clé privée de signature des jetons
$keysAcl = Get-Acl "$InstallDir\data\keys"
$keysAcl.SetAccessRuleProtection($true, $false)
foreach ($identity in @("BUILTIN\Administrators", "NT AUTHORITY\SYSTEM")) {
    $keysAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($identity, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow")))
}
Set-Acl "$InstallDir\data\keys" $keysAcl

# PostgreSQL : initialisation au premier lancement
$pgData = "$InstallDir\data\pgdata"
$pgBin = "$InstallDir\pgsql\bin"
if (-not (Test-Path "$pgData\PG_VERSION")) {
    Write-Host "== Initialisation de la base de données"
    $pwFile = "$env:TEMP\pharmacore-su.txt"
    Set-Content -Path $pwFile -Value (New-Secret) -NoNewline
    & "$pgBin\initdb.exe" -D $pgData -U postgres --pwfile=$pwFile -E UTF8 --locale=fr-FR -A scram-sha-256 | Out-Null
    Remove-Item $pwFile
    Add-Content "$pgData\postgresql.conf" @"
# PharmaCore : durabilité maximale (coupures de courant) et empreinte modeste
port = 5433
listen_addresses = '127.0.0.1'
synchronous_commit = on
fsync = on
full_page_writes = on
wal_level = replica
shared_buffers = 256MB
max_connections = 40
log_min_duration_statement = 2000
"@
    & "$pgBin\pg_ctl.exe" start -D $pgData -w -t 120 -o "-p 5433" | Out-Null
    $initSql = (Get-Content "$source\init.sql" -Raw)
    $initSql | & "$pgBin\psql.exe" -v ON_ERROR_STOP=1 -p 5433 -U postgres -d postgres `
        -v owner_password="'$env:PHARMACORE_DB_OWNER_PASSWORD'" -v app_password="'$env:PHARMACORE_DB_APP_PASSWORD'" -f - | Out-Null
    & "$pgBin\pg_ctl.exe" stop -D $pgData -m fast -w | Out-Null
}

# Services Windows
Write-Host "== Enregistrement des services"
foreach ($svc in @("PharmaCorePostgres", "PharmaCoreHub")) {
    $exe = "$InstallDir\$svc.exe"
    if (Get-Service -Name $svc -ErrorAction SilentlyContinue) {
        & $exe stop | Out-Null
        & $exe refresh | Out-Null
    } else {
        & $exe install | Out-Null
    }
}
& "$InstallDir\PharmaCorePostgres.exe" start | Out-Null
& "$InstallDir\PharmaCoreHub.exe" start | Out-Null

# Pare-feu : les postes de l'officine (réseau privé) atteignent le hub sur 8080
if (-not (Get-NetFirewallRule -DisplayName "PharmaCore Hub" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName "PharmaCore Hub" -Direction Inbound -Protocol TCP -LocalPort 8080 -Profile Private -Action Allow | Out-Null
}

# Sauvegarde quotidienne à 22:30 (après la fermeture) + à chaque démarrage
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$InstallDir\backup.ps1`" -InstallDir `"$InstallDir`" -BackupDir `"$BackupDir`""
$triggers = @((New-ScheduledTaskTrigger -Daily -At 22:30), (New-ScheduledTaskTrigger -AtStartup))
Register-ScheduledTask -TaskName "PharmaCore Backup" -Action $action -Trigger $triggers -RunLevel Highest -User "SYSTEM" -Force | Out-Null

Write-Host ""
Write-Host "Installation terminée."
Write-Host "Ouvrez http://localhost:8080 sur ce poste (ou http://$env:COMPUTERNAME:8080 depuis les autres postes)"
Write-Host "et saisissez le code d'appairage fourni par PharmaCore."
