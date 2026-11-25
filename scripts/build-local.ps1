$ErrorActionPreference = "Stop"

$SdkRoot = "D:\code\android-sdk"
$JdkHome = "$SdkRoot\openjdk\jdk-27.0.1.1"
$SdkManager = "$SdkRoot\cli-tools\bin\sdkmanager.bat"

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$SdkRoot\platform;$SdkRoot\cli-tools\bin;$env:PATH"

if (-not (Test-Path "$SdkRoot\platforms\android-36")) {
    Write-Host "Installing Android 16 (API 36) platform and build-tools..."
    & $SdkManager --sdk_root=$SdkRoot "platforms;android-36" "build-tools;36.0.0"
}

Set-Content -Path "$PSScriptRoot\..\local.properties" -Value "sdk.dir=$($SdkRoot -replace '\\','\\')" -Encoding ASCII

if (Get-Command gradle -ErrorAction SilentlyContinue) {
    Push-Location "$PSScriptRoot\.."
    try {
        gradle assembleRelease --no-daemon
    } finally {
        Pop-Location
    }
} else {
    Write-Host "Gradle is not on PATH. Install Gradle 8.13+ or push to GitHub to build via .github/workflows/ci.yml."
}
