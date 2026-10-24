<#
.SYNOPSIS
  Construit le paquet d'installation Windows du hub (exécuté par GitHub Actions sur windows-latest,
  ou localement avec JDK 26, Maven, Node 24).
  Produit dist\PharmaCoreHub-<version>.zip : app\ (jar), runtime\ (JRE réduit par jlink),
  web\ (PWA), pgsql\ (PostgreSQL portable), winsw.exe et les scripts d'installation.
#>
param(
    [string]$Version = "0.2.0",
    [string]$PostgresVersion = "17.6-1",
    [string]$WinSwVersion = "v3.0.0-alpha.11"
)
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\.."
$out = "$root\dist\hub"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$out\app", "$out\web", "$out\pgsql" | Out-Null

Write-Host "== Backend"
Push-Location "$root\backend"
mvn -B -q -DskipTests package
Copy-Item (Get-ChildItem target\pharma-core-backend-*.jar | Where-Object { $_.Name -notlike "*original*" } | Select-Object -First 1).FullName "$out\app\pharmacore-hub.jar"
Pop-Location

Write-Host "== Frontend"
Push-Location "$root\frontend"
npm ci
npm run build
Copy-Item "dist\frontend\browser\*" "$out\web" -Recurse -Force
Pop-Location

Write-Host "== Runtime Java réduit (jlink)"
$modules = (& jdeps --ignore-missing-deps --multi-release 26 --print-module-deps "$out\app\pharmacore-hub.jar")
$modules = "$modules,jdk.crypto.ec,jdk.localedata,jdk.zipfs,java.management,java.instrument,jdk.unsupported,java.security.jgss,java.net.http,jdk.management"
& jlink --add-modules $modules --strip-debug --no-man-pages --no-header-files --compress zip-6 --output "$out\runtime"

Write-Host "== PostgreSQL portable"
$pgZip = "$env:TEMP\postgresql-$PostgresVersion-windows-x64-binaries.zip"
Invoke-WebRequest -Uri "https://get.enterprisedb.com/postgresql/postgresql-$PostgresVersion-windows-x64-binaries.zip" -OutFile $pgZip
Expand-Archive $pgZip -DestinationPath "$env:TEMP\pg" -Force
Copy-Item "$env:TEMP\pg\pgsql\*" "$out\pgsql" -Recurse -Force
Remove-Item "$out\pgsql\pgAdmin 4", "$out\pgsql\symbols", "$out\pgsql\doc", "$out\pgsql\include" -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "== WinSW"
Invoke-WebRequest -Uri "https://github.com/winsw/winsw/releases/download/$WinSwVersion/WinSW-x64.exe" -OutFile "$out\winsw.exe"

Copy-Item "$PSScriptRoot\*.xml", "$PSScriptRoot\*.ps1", "$PSScriptRoot\init.sql" $out -Force
Remove-Item "$out\build-hub.ps1" -ErrorAction SilentlyContinue

Write-Host "== Archive"
New-Item -ItemType Directory -Force -Path "$root\dist" | Out-Null
Compress-Archive -Path "$out\*" -DestinationPath "$root\dist\PharmaCoreHub-$Version.zip" -Force
Write-Host "Paquet : $root\dist\PharmaCoreHub-$Version.zip"
