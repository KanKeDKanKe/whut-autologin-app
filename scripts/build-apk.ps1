param(
    [string]$ProjectRoot = "E:\whut-autologin-app",
    [string]$JavaHome = "",
    [ValidateSet("Release", "Debug")]
    [string]$Variant = "Release"
)

$ErrorActionPreference = "Stop"

function Find-JavaHome {
    if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
        return $JavaHome
    }

    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }

    $candidates = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre",
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Java\jdk-17"
    )

    foreach ($path in $candidates) {
        if (Test-Path (Join-Path $path "bin\java.exe")) {
            return $path
        }
    }

    return $null
}

$javaHome = Find-JavaHome
if (-not $javaHome) {
    Write-Host "[ERROR] Java not found. Please install Android Studio or JDK." -ForegroundColor Red
    exit 1
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;" + $env:Path

Write-Host "Using JAVA_HOME=$javaHome"

Push-Location $ProjectRoot
try {
    if ($Variant -eq "Release") {
        .\gradlew.bat assembleRelease
        Write-Host "[OK] Release APK: app\build\outputs\apk\release\app-release.apk" -ForegroundColor Green
    } else {
        .\gradlew.bat assembleDebug
        Write-Host "[OK] Debug APK: app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
    }
}
finally {
    Pop-Location
}
