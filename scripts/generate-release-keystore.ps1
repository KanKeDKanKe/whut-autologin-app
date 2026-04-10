param(
    [string]$ProjectRoot = "E:\whut-autologin-app",
    [string]$JavaHome = "",
    [string]$KeytoolPath = ""
)

$ErrorActionPreference = "Stop"

function Find-Keytool {
    if ($KeytoolPath -and (Test-Path $KeytoolPath)) {
        return $KeytoolPath
    }

    $candidates = @()

    if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\keytool.exe"))) {
        $candidates += (Join-Path $JavaHome "bin\keytool.exe")
    }

    if ($env:JAVA_HOME) {
        $candidates += (Join-Path $env:JAVA_HOME "bin\keytool.exe")
    }

    $candidates += @(
        "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
        "C:\Program Files\Android\Android Studio\jre\bin\keytool.exe",
        "C:\Program Files\Java\jdk-17\bin\keytool.exe",
        "C:\Program Files\Java\jdk-21\bin\keytool.exe"
    )

    foreach ($path in $candidates) {
        if ($path -and (Test-Path $path)) {
            return $path
        }
    }

    $fromPath = Get-Command keytool -ErrorAction SilentlyContinue
    if ($fromPath) {
        return $fromPath.Source
    }

    return $null
}

$keytool = Find-Keytool
if (-not $keytool) {
    Write-Host "[ERROR] keytool not found. Please install JDK or Android Studio, then retry." -ForegroundColor Red
    exit 1
}

$keystoreDir = Join-Path $ProjectRoot "keystore"
$keystorePath = Join-Path $keystoreDir "release.jks"
$keyPropsPath = Join-Path $ProjectRoot "key.properties"

New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null

Write-Host "Using keytool: $keytool"
Write-Host "Keystore output: $keystorePath"

$alias = Read-Host "Key alias (default: whut_release)"
if ([string]::IsNullOrWhiteSpace($alias)) { $alias = "whut_release" }

$storePassword = Read-Host "Store password"
$keyPassword = Read-Host "Key password (leave empty to use store password)"
if ([string]::IsNullOrWhiteSpace($keyPassword)) { $keyPassword = $storePassword }

$dname = Read-Host "DName (default: CN=WHUT Auto Login, OU=Dev, O=WHUT, L=Wuhan, ST=Hubei, C=CN)"
if ([string]::IsNullOrWhiteSpace($dname)) {
    $dname = "CN=WHUT Auto Login, OU=Dev, O=WHUT, L=Wuhan, ST=Hubei, C=CN"
}

if (Test-Path $keystorePath) {
    $overwrite = Read-Host "Keystore already exists. Overwrite? (y/N)"
    if ($overwrite -notin @("y", "Y")) {
        Write-Host "Aborted."
        exit 0
    }
    Remove-Item -LiteralPath $keystorePath -Force
}

& $keytool -genkeypair `
  -v `
  -keystore $keystorePath `
  -storepass $storePassword `
  -alias $alias `
  -keypass $keyPassword `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000 `
  -dname $dname

@"
storeFile=keystore/release.jks
storePassword=$storePassword
keyAlias=$alias
keyPassword=$keyPassword
"@ | Set-Content -Path $keyPropsPath -Encoding UTF8

Write-Host ""
Write-Host "[OK] Release signing configured." -ForegroundColor Green
Write-Host "key.properties: $keyPropsPath"
Write-Host "keystore: $keystorePath"
Write-Host ""
Write-Host "Next: .\gradlew.bat assembleRelease"
