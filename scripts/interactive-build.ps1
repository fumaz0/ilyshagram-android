#Requires -Version 5.1
<#
.SYNOPSIS
  Interactive OwpenGram Android build helper for Windows.

.DESCRIPTION
  Patches server DC options and API credentials, updates submodules,
  ensures Android SDK / local.properties, and builds via Gradle.
#>
[CmdletBinding()]
param(
    [switch]$NoMenu
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$ConnectionsManagerFile = Join-Path $RepoRoot 'TMessagesProj\jni\tgnet\ConnectionsManager.cpp'
$DatacenterFile = Join-Path $RepoRoot 'TMessagesProj\jni\tgnet\Datacenter.h'
$BuildVarsFile = Join-Path $RepoRoot 'TMessagesProj\src\main\java\org\telegram\messenger\BuildVars.java'
$LocalPropertiesFile = Join-Path $RepoRoot 'local.properties'
$ConfigFile = Join-Path $RepoRoot '.owpengram-build.local.json'
$GradlewBat = Join-Path $RepoRoot 'gradlew.bat'

$TestApiId = '17349'
$TestApiHash = '344583e45741c457fe1862106095a5eb'

$RequiredPlatform = 'android-35'
$RequiredBuildTools = '35.0.0'
$RequiredNdk = '21.4.7075529'

function Write-Title([string]$Text) {
    Write-Host ''
    Write-Host ('=' * 60) -ForegroundColor Cyan
    Write-Host $Text -ForegroundColor Cyan
    Write-Host ('=' * 60) -ForegroundColor Cyan
}

function Write-Step([string]$Text) { Write-Host "`n>> $Text" -ForegroundColor Yellow }
function Write-Ok([string]$Text)   { Write-Host "[OK] $Text" -ForegroundColor Green }
function Write-WarnMsg([string]$Text) { Write-Host "[!] $Text" -ForegroundColor DarkYellow }
function Write-ErrMsg([string]$Text)  { Write-Host "[X] $Text" -ForegroundColor Red }

function Read-Default([string]$Prompt, [string]$Default) {
    $suffix = if ([string]::IsNullOrWhiteSpace($Default)) { '' } else { " [$Default]" }
    $value = Read-Host "$Prompt$suffix"
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value.Trim()
}

function Read-YesNo([string]$Prompt, [bool]$DefaultYes = $true) {
    $hint = if ($DefaultYes) { 'Y/n' } else { 'y/N' }
    $value = (Read-Host "$Prompt ($hint)").Trim().ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($value)) { return $DefaultYes }
    return $value -in @('y', 'yes', 't', '1')
}

function Read-Choice([string]$Prompt, [string[]]$Options, [int]$DefaultIndex = 0) {
    for ($i = 0; $i -lt $Options.Length; $i++) {
        Write-Host "  $($i + 1)) $($Options[$i])"
    }
    $raw = Read-Default $Prompt ([string]($DefaultIndex + 1))
    $idx = 0
    if (-not [int]::TryParse($raw, [ref]$idx) -or $idx -lt 1 -or $idx -gt $Options.Length) {
        $idx = $DefaultIndex + 1
    }
    return $Options[$idx - 1]
}

function Get-DefaultConfig {
    @{
        ServerHost          = '192.168.100.10'
        ServerPort          = 2398
        ApiId               = $TestApiId
        ApiHash             = $TestApiHash
        UseTestApi          = $true
        BuildType           = 'debug'
        SdkPath             = ''
        InstallAfterBuild   = $false
    }
}

$script:ResolvedSdkPath = $null

function Load-Config {
    param([switch]$Quiet)

    $cfg = Get-DefaultConfig
    if (Test-Path $ConfigFile) {
        try {
            $saved = Get-Content $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
            foreach ($key in @($cfg.Keys)) {
                if ($saved.PSObject.Properties.Name -contains $key) {
                    $cfg[$key] = $saved.$key
                }
            }
            if (-not $Quiet) {
                Write-Ok "Loaded saved settings from $(Split-Path -Leaf $ConfigFile)"
            }
        }
        catch {
            Write-WarnMsg "Could not read config file, using defaults."
        }
    }
    return $cfg
}

function Save-Config($Cfg) {
    $Cfg | ConvertTo-Json | Set-Content -Path $ConfigFile -Encoding UTF8
    $script:Cfg = Load-Config -Quiet
    Write-Ok "Settings saved to $(Split-Path -Leaf $ConfigFile) (gitignored)"
}

function Get-SdkCandidates {
    $found = @{}
    $add = {
        param($Path)
        if (-not $Path -or -not (Test-Path $Path)) { return }
        $resolved = (Resolve-Path $Path).Path
        if (-not $found.ContainsKey($resolved)) {
            $found[$resolved] = $resolved
        }
    }

    foreach ($manual in @($script:Cfg.SdkPath, $env:OWPNG_ANDROID_SDK, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        & $add $manual
    }
    & $add (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    & $add (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk')

    return [object[]](@($found.Values | Sort-Object))
}

function Resolve-SdkPath {
    param(
        [hashtable]$Cfg,
        [switch]$AllowPrompt
    )

    if ($script:ResolvedSdkPath -and (Test-Path $script:ResolvedSdkPath)) {
        return $script:ResolvedSdkPath
    }

    $candidates = @(Get-SdkCandidates)
    if ($candidates.Count -eq 1) {
        $script:ResolvedSdkPath = $candidates[0]
        return $script:ResolvedSdkPath
    }

    if ($candidates.Count -gt 1 -and -not $AllowPrompt) {
        $preferred = $candidates | Where-Object { $_ -match '\\Android\\Sdk$' } | Select-Object -First 1
        if ($preferred) {
            $script:ResolvedSdkPath = $preferred
            return $script:ResolvedSdkPath
        }
        $script:ResolvedSdkPath = $candidates[0]
        return $script:ResolvedSdkPath
    }

    if ($AllowPrompt -and $candidates.Count -gt 0) {
        Write-Host ''
        Write-Host 'Multiple Android SDK paths found. Pick one:' -ForegroundColor White
        for ($i = 0; $i -lt $candidates.Count; $i++) {
            Write-Host "  $($i + 1)) $($candidates[$i])"
        }
        $pick = Read-Default 'Number' '1'
        $idx = [int]$pick - 1
        if ($idx -ge 0 -and $idx -lt $candidates.Count) {
            $script:ResolvedSdkPath = $candidates[$idx]
            $Cfg.SdkPath = $script:ResolvedSdkPath
            Save-Config $Cfg
            return $script:ResolvedSdkPath
        }
    }

    if ($AllowPrompt) {
        Write-Host ''
        Write-WarnMsg 'Paste full path to Android SDK, for example:'
        Write-Host '  C:\Users\you\AppData\Local\Android\Sdk' -ForegroundColor DarkGray
        $manualPath = Read-Host 'Path (empty = cancel)'
        if ($manualPath -and (Test-Path $manualPath)) {
            $script:ResolvedSdkPath = (Resolve-Path $manualPath).Path
            $Cfg.SdkPath = $script:ResolvedSdkPath
            Save-Config $Cfg
            return $script:ResolvedSdkPath
        }
    }

    return $null
}

function Get-JavaVersionOutput {
    $prevErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return ((& java -version 2>&1 | ForEach-Object { "$_" }) | Select-Object -First 1)
    }
    finally {
        $ErrorActionPreference = $prevErrorAction
    }
}

function Test-JavaVersion {
    $prevErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& java -version 2>&1 | ForEach-Object { "$_" }) -join "`n"
    }
    finally {
        $ErrorActionPreference = $prevErrorAction
    }

    if ([string]::IsNullOrWhiteSpace($output)) {
        return $null
    }
    if ($output -match 'version "(\d+)') {
        return [int]$Matches[1]
    }
    if ($output -match 'version "1\.(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Test-SdkComponents([string]$SdkPath) {
    $missing = @()
    $checks = @(
        @{ Label = "platforms;$RequiredPlatform"; Path = Join-Path $SdkPath "platforms\$RequiredPlatform" },
        @{ Label = "build-tools;$RequiredBuildTools"; Path = Join-Path $SdkPath "build-tools\$RequiredBuildTools" },
        @{ Label = "ndk;$RequiredNdk"; Path = Join-Path $SdkPath "ndk\$RequiredNdk" }
    )
    foreach ($check in $checks) {
        if (-not (Test-Path $check.Path)) {
            $missing += $check.Label
        }
    }
    return [object[]]$missing
}

function Write-SdkDiagnostics {
    Write-WarnMsg 'Android SDK was not resolved automatically.'
    Write-Host '  Set ANDROID_HOME / ANDROID_SDK_ROOT, install Android Studio, or set SdkPath in settings.'
    $candidates = @(Get-SdkCandidates)
    if ($candidates.Count -gt 0) {
        Write-Host '  SDK candidates on disk:'
        $candidates | ForEach-Object { Write-Host "    $_" }
    }
}

function Test-Prerequisites {
    param([hashtable]$Cfg)

    Write-Step 'Checking prerequisites'

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw 'Missing git in PATH. Install Git for Windows.'
    }
    Write-Ok "git: $(git --version)"

    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Missing java in PATH. Install JDK 17+ and set JAVA_HOME.'
    }
    $javaMajor = Test-JavaVersion
    if (-not $javaMajor -or $javaMajor -lt 17) {
        throw "JDK 17+ required (found: $(if ($javaMajor) { $javaMajor } else { 'unknown' })). Install JDK 17 and set JAVA_HOME."
    }
    Write-Ok "java: $(Get-JavaVersionOutput)"

    if (-not (Test-Path $GradlewBat)) {
        throw "Gradle wrapper missing: $GradlewBat"
    }
    Write-Ok "Gradle wrapper: $GradlewBat"

    $sdk = Resolve-SdkPath -Cfg $Cfg -AllowPrompt
    if (-not $sdk) {
        Write-SdkDiagnostics
        throw @"
Could not find Android SDK.
Install Android Studio and SDK Platform 35, Build-Tools 35.0.0, NDK 21.4.7075529.
Or set SdkPath manually in menu (1) / env OWPNG_ANDROID_SDK.
"@
    }
    Write-Ok "Android SDK: $sdk"

    $missingComponents = @(Test-SdkComponents $sdk)
    if ($missingComponents.Count -gt 0) {
        throw @"
Android SDK is missing components: $($missingComponents -join ', ').
Open Android Studio -> SDK Manager and install them, or run sdkmanager $($missingComponents -join ' ').
"@
    }
    Write-Ok 'Required SDK components are present'

    return $sdk
}

function Format-LocalPropertiesSdkPath([string]$SdkPath) {
    return ($SdkPath -replace '\\', '\\')
}

function Ensure-LocalProperties {
    param([string]$SdkPath)

    $escaped = Format-LocalPropertiesSdkPath $SdkPath
    $expectedLine = "sdk.dir=$escaped"

    if (Test-Path $LocalPropertiesFile) {
        $content = Get-Content -Path $LocalPropertiesFile -Raw -Encoding UTF8
        if ($content -match '(?m)^sdk\.dir=.*$') {
            $updated = [regex]::Replace($content, '(?m)^sdk\.dir=.*$', $expectedLine)
            if ($updated -ne $content) {
                [System.IO.File]::WriteAllText($LocalPropertiesFile, $updated, (New-Object System.Text.UTF8Encoding $false))
                Write-Ok "Updated sdk.dir in local.properties"
            }
            else {
                Write-Ok 'local.properties sdk.dir is already correct'
            }
            return
        }
    }

    $line = "$expectedLine`r`n"
    [System.IO.File]::WriteAllText($LocalPropertiesFile, $line, (New-Object System.Text.UTF8Encoding $false))
    Write-Ok "Created local.properties with sdk.dir"
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory)][string]$Task,
        [string]$Label = ''
    )

    if (-not (Test-Path $GradlewBat)) {
        throw "Gradle wrapper missing: $GradlewBat"
    }

    $logDir = Join-Path $RepoRoot 'logs'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $safeName = ($Label -replace '[^\w\-]+', '_').Trim('_')
    if ([string]::IsNullOrWhiteSpace($safeName)) {
        $safeName = ($Task -replace '[^\w\-]+', '_').Trim('_')
    }
    $logFile = Join-Path $logDir ("{0}-{1}.log" -f $safeName, (Get-Date -Format 'yyyyMMdd-HHmmss'))

    $escapedWd = $RepoRoot.Replace('"', '""')
    $escapedLog = $logFile.Replace('"', '""')
    $escapedGradlew = $GradlewBat.Replace('"', '""')
    $full = "cd /d `"$escapedWd`" && call `"$escapedGradlew`" $Task >> `"$escapedLog`" 2>&1"

    $title = if ($Label) { $Label } else { $Task }
    Write-Host "gradle: $Task" -ForegroundColor DarkGray
    Write-Host "log: $logFile" -ForegroundColor DarkGray
    Write-Host ''
    Write-WarnMsg @"
Long step started at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss').
Output goes to the log file; the console shows new lines + a heartbeat every 30s.
First native build can take 30+ minutes.
Tip: run build-android.cmd from PowerShell or cmd if Git Bash shows only blank lines.
"@

    if (Test-Path $logFile) { Remove-Item $logFile -Force }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'cmd.exe'
    $psi.Arguments = "/c `"$full`""
    $psi.WorkingDirectory = $RepoRoot
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    $null = $process.Start()

    $logOffset = 0L
    $lastHeartbeat = [datetime]::UtcNow

    while (-not $process.HasExited) {
        if (Test-Path $logFile) {
            $stream = [System.IO.File]::Open($logFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
            try {
                $stream.Seek($logOffset, [System.IO.SeekOrigin]::Begin) | Out-Null
                $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
                while (-not $reader.EndOfStream) {
                    $line = $reader.ReadLine()
                    if ([string]::IsNullOrWhiteSpace($line)) { continue }
                    Write-Host $line
                }
                $logOffset = $stream.Position
            }
            finally {
                $stream.Dispose()
            }
        }

        $now = [datetime]::UtcNow
        if (($now - $lastHeartbeat).TotalSeconds -ge 30) {
            $lastHeartbeat = $now
            $sizeKb = if (Test-Path $logFile) { [math]::Round((Get-Item $logFile).Length / 1KB, 1) } else { 0 }
            Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $title - still running (log ${sizeKb} KB)..." -ForegroundColor Cyan
        }
        Start-Sleep -Milliseconds 500
    }

    if (Test-Path $logFile) {
        $tail = Get-Content -Path $logFile -Tail 20 -ErrorAction SilentlyContinue |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        if ($tail) {
            Write-Host ''
            Write-Host '--- last log lines ---' -ForegroundColor DarkGray
            $tail | ForEach-Object { Write-Host $_ }
        }
    }

    $exitCode = $process.ExitCode
    Write-Host ''
    if ($exitCode -ne 0) {
        if (Test-Path $logFile) {
            $logText = Get-Content -Path $logFile -Raw -ErrorAction SilentlyContinue
            if ($logText -match 'Permission denied|unable to rename temporary') {
                throw @"
Gradle native build failed: file lock on Windows (Permission denied).
Close Android Studio and other Gradle builds, then run:
  gradlew.bat --stop
Delete native cache if it repeats:
  rmdir /s /q TMessagesProj\.cxx
Then retry the build. Antivirus can also lock .cxx object files.
See log: $logFile
"@
            }
        }
        throw "Gradle failed (exit $exitCode): $Task`nSee log: $logFile"
    }
    Write-Ok "Finished: $title"
}

function Set-ServerEndpoint {
    param([string]$ServerAddress, [int]$Port)

    if (-not (Test-Path $ConnectionsManagerFile)) {
        throw "ConnectionsManager.cpp not found: $ConnectionsManagerFile"
    }

    $content = Get-Content -Path $ConnectionsManagerFile -Raw -Encoding UTF8
    $pattern = 'TcpAddress\("([^"]*)",\s*(\d+),\s*0,\s*""\)'

    $matches = [regex]::Matches($content, $pattern)
    if ($matches.Count -lt 2) {
        throw 'Failed to patch ConnectionsManager.cpp - expected at least 2 TcpAddress placeholders.'
    }

    $alreadySet = $true
    foreach ($m in $matches) {
        if ($m.Groups[1].Value -ne $ServerAddress -or [int]$m.Groups[2].Value -ne $Port) {
            $alreadySet = $false
            break
        }
    }
    if ($alreadySet) {
        Write-Ok "Server endpoint already set to ${ServerAddress}:$Port in ConnectionsManager.cpp"
    }
    else {
        $newContent = [regex]::Replace($content, $pattern, {
            param($m)
            'TcpAddress("' + $ServerAddress + '", ' + $Port + ', 0, "")'
        })
        if ($newContent -eq $content) {
            throw 'Failed to patch ConnectionsManager.cpp - replacement produced no changes.'
        }
        [System.IO.File]::WriteAllText($ConnectionsManagerFile, $newContent, (New-Object System.Text.UTF8Encoding $false))
        Write-Ok "Server endpoint patched in ConnectionsManager.cpp: ${ServerAddress}:$Port"
    }

    if (-not (Test-Path $DatacenterFile)) {
        throw "Datacenter.h not found: $DatacenterFile"
    }

    $dcContent = Get-Content -Path $DatacenterFile -Raw -Encoding UTF8
    $portPattern = 'const int32_t \*defaultPorts = new int32_t\[4\] \{-1,\s*(\d+),\s*-1,\s*-1\};'
    if ($dcContent -notmatch $portPattern) {
        throw 'Failed to patch Datacenter.h - defaultPorts line not found.'
    }

    if ([int]$Matches[1] -eq $Port) {
        Write-Ok "Datacenter.h port already set to $Port"
        return
    }

    $newDcContent = [regex]::Replace($dcContent, $portPattern, "const int32_t *defaultPorts = new int32_t[4] {-1, $Port, -1, -1};")
    [System.IO.File]::WriteAllText($DatacenterFile, $newDcContent, (New-Object System.Text.UTF8Encoding $false))
    Write-Ok "Datacenter.h port patched: $Port"
}

function Set-ApiCredentials {
    param([string]$ApiId, [string]$ApiHash)

    if (-not (Test-Path $BuildVarsFile)) {
        throw "BuildVars.java not found: $BuildVarsFile"
    }

    $content = Get-Content -Path $BuildVarsFile -Raw -Encoding UTF8
    $idPattern = 'public static int APP_ID = (\d+);'
    $hashPattern = 'public static String APP_HASH = "([^"]*)";'

    $idMatch = [regex]::Match($content, $idPattern)
    $hashMatch = [regex]::Match($content, $hashPattern)
    if (-not $idMatch.Success -or -not $hashMatch.Success) {
        throw 'Failed to patch BuildVars.java - APP_ID / APP_HASH not found.'
    }

    if ($idMatch.Groups[1].Value -eq $ApiId -and $hashMatch.Groups[1].Value -eq $ApiHash) {
        Write-Ok "API credentials already set (APP_ID=$ApiId)"
        return
    }

    $newContent = [regex]::Replace($content, $idPattern, "public static int APP_ID = $ApiId;")
    $newContent = [regex]::Replace($newContent, $hashPattern, "public static String APP_HASH = `"$ApiHash`";")

    if ($newContent -eq $content) {
        throw 'Failed to patch BuildVars.java - replacement produced no changes.'
    }

    [System.IO.File]::WriteAllText($BuildVarsFile, $newContent, (New-Object System.Text.UTF8Encoding $false))
    Write-Ok "API credentials patched: APP_ID=$ApiId"
}

function Update-Submodules {
    Write-Step 'Updating git submodules (may take a while)'
    Push-Location $RepoRoot
    try {
        $env:GIT_TERMINAL_PROMPT = '0'
        & git -c advice.detachedHead=false submodule update --init --recursive --quiet
        if ($LASTEXITCODE -ne 0) { throw "git submodule failed with exit $LASTEXITCODE" }
        Write-Ok 'Submodules are up to date'
    }
    finally {
        Pop-Location
    }
}

function Get-GradleAssembleTask([hashtable]$Cfg) {
    $buildType = if ($Cfg.BuildType -eq 'release') { 'Release' } else { 'Debug' }
    return ":TMessagesProj_App:assembleAfat$buildType"
}

function Get-GradleInstallTask([hashtable]$Cfg) {
    $buildType = if ($Cfg.BuildType -eq 'release') { 'Release' } else { 'Debug' }
    return ":TMessagesProj_App:installAfat$buildType"
}

function Get-ApkPath([hashtable]$Cfg) {
    $folder = if ($Cfg.BuildType -eq 'release') { 'release' } else { 'debug' }
    return Join-Path $RepoRoot "TMessagesProj_App\build\outputs\apk\afat\$folder\app.apk"
}

function Start-GradleBuild {
    param([hashtable]$Cfg)

    if ($Cfg.BuildType -eq 'release') {
        Write-WarnMsg 'Release build uses R8/minify and takes significantly longer than debug.'
    }
    else {
        Write-WarnMsg 'First debug build compiles native code (NDK/CMake) and can take 30+ minutes.'
    }

    $task = Get-GradleAssembleTask $Cfg
    Invoke-Gradle -Task $task -Label "assemble-$($Cfg.BuildType)"

    $apk = Get-ApkPath $Cfg
    if (Test-Path $apk) {
        Write-Ok "APK: $apk"
    }
    else {
        Write-WarnMsg "Build finished but APK not found: $apk"
    }
}

function Test-AdbDevice {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        return $false
    }
    $devices = & adb devices 2>$null | Where-Object { $_ -match "`tdevice$" }
    return @($devices).Count -gt 0
}

function Start-GradleInstall {
    param([hashtable]$Cfg)

    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        throw 'adb not found in PATH. Install Android SDK Platform-Tools or add them to PATH.'
    }
    if (-not (Test-AdbDevice)) {
        throw 'No connected Android device/emulator found. Run adb devices and authorize USB debugging.'
    }

    $task = Get-GradleInstallTask $Cfg
    Invoke-Gradle -Task $task -Label "install-$($Cfg.BuildType)"
    Write-Ok 'App installed on connected device'
}

function Open-ApkFolder {
    param([hashtable]$Cfg)

    $apk = Get-ApkPath $Cfg
    $folder = Split-Path -Parent $apk
    if (-not (Test-Path $folder)) {
        throw "APK output folder not found (build first): $folder"
    }
    Write-Step 'Opening APK output folder'
    Start-Process explorer.exe $folder
}

function Find-AndroidStudioExe {
    $candidates = @(
        (Join-Path ${env:ProgramFiles} 'Android\Android Studio\bin\studio64.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Android\Android Studio\bin\studio64.exe'),
        (Join-Path ${env:LocalAppData} 'Programs\Android Studio\bin\studio64.exe')
    )
    foreach ($path in $candidates) {
        if (Test-Path $path) { return $path }
    }
    return $null
}

function Open-AndroidStudio {
    $studio = Find-AndroidStudioExe
    if (-not $studio) {
        throw 'Android Studio not found. Install it or open the project folder manually.'
    }
    Write-Step 'Opening project in Android Studio'
    Start-Process $studio -ArgumentList $RepoRoot
}

function Show-ConfigSummary([hashtable]$Cfg) {
    Write-Host ''
    Write-Host 'Current settings:' -ForegroundColor White
    Write-Host "  Server:      $($Cfg.ServerHost):$($Cfg.ServerPort)"
    Write-Host "  API ID:      $($Cfg.ApiId) $(if ($Cfg.UseTestApi) { '(test credentials)' } else { '(custom)' })"
    Write-Host "  Build type:  $($Cfg.BuildType) -> $(Get-GradleAssembleTask $Cfg)"
    Write-Host "  SDK:         $(if ($script:ResolvedSdkPath) { $script:ResolvedSdkPath } elseif ($Cfg.SdkPath) { $Cfg.SdkPath } else { '(auto-detect)' })"
    $apk = Get-ApkPath $Cfg
    Write-Host "  APK:         $(if (Test-Path $apk) { 'exists' } else { 'not built yet' })"
}

function Edit-ConfigInteractive {
    $Cfg = $script:Cfg
    Write-Title 'Settings'

    $Cfg.ServerHost = Read-Default 'Server IP or hostname' $Cfg.ServerHost
    $portRaw = Read-Default 'MTProto TCP port' ([string]$Cfg.ServerPort)
    $portNum = 0
    if (-not [int]::TryParse($portRaw, [ref]$portNum)) {
        throw 'Port must be a number.'
    }
    $Cfg.ServerPort = $portNum

    $Cfg.UseTestApi = Read-YesNo 'Use Telegram TEST api_id/api_hash (local dev only)?' $Cfg.UseTestApi
    if ($Cfg.UseTestApi) {
        $Cfg.ApiId = $TestApiId
        $Cfg.ApiHash = $TestApiHash
        Write-WarnMsg 'Test credentials must not be used in production deployments.'
    }
    else {
        $Cfg.ApiId = Read-Default 'api_id' $Cfg.ApiId
        $Cfg.ApiHash = Read-Default 'api_hash' $Cfg.ApiHash
    }

    $Cfg.BuildType = Read-Choice 'Build type' @('debug', 'release') $(if ($Cfg.BuildType -eq 'release') { 1 } else { 0 })
    $Cfg.InstallAfterBuild = Read-YesNo 'Install to device after full pipeline build?' $Cfg.InstallAfterBuild

    Write-Host ''
    Write-Host "Current SDK: $(if ($Cfg.SdkPath) { $Cfg.SdkPath } else { '(auto-detect)' })"
    if (Read-YesNo 'Set Android SDK path manually?' $false) {
        $manual = Read-Host 'Full path to Android SDK'
        if ($manual -and (Test-Path $manual)) {
            $Cfg.SdkPath = (Resolve-Path $manual).Path
            $script:ResolvedSdkPath = $Cfg.SdkPath
        }
        elseif ($manual) {
            Write-WarnMsg 'Path not found, keeping auto-detect.'
        }
    }

    Save-Config $Cfg

    if (Read-YesNo "Apply server address patch to source files now ($($Cfg.ServerHost):$($Cfg.ServerPort))?" $false) {
        Set-ServerEndpoint -ServerAddress $Cfg.ServerHost -Port $Cfg.ServerPort
    }
}

function Run-StepPipeline {
    param([string[]]$Steps)

    $Cfg = $script:Cfg
    foreach ($step in $Steps) {
        switch ($step) {
            'patch'        { Set-ApiCredentials -ApiId $Cfg.ApiId -ApiHash $Cfg.ApiHash }
            'patch-server' { Set-ServerEndpoint -ServerAddress $Cfg.ServerHost -Port $Cfg.ServerPort }
            'patch-api'    { Set-ApiCredentials -ApiId $Cfg.ApiId -ApiHash $Cfg.ApiHash }
            'submodules'   { Update-Submodules }
            'sdk'          { Ensure-LocalProperties -SdkPath $script:ResolvedSdkPath }
            'build'        { Start-GradleBuild -Cfg $Cfg }
            'install'      { Start-GradleInstall -Cfg $Cfg }
            'open-apk'     { Open-ApkFolder -Cfg $Cfg }
            'open-studio'  { Open-AndroidStudio }
            default        { throw "Unknown step: $step" }
        }
    }
}

function Show-Menu {
    $Cfg = $script:Cfg
    Show-ConfigSummary $Cfg
    Write-Host ''
    Write-Host 'Actions:' -ForegroundColor White
    Write-Host '  1) Edit settings'
    Write-Host '  2) Patch server IP/port only'
    Write-Host '  3) Patch API credentials only'
    Write-Host '  4) Update git submodules'
    Write-Host '  5) Ensure SDK / local.properties'
    Write-Host '  6) Build APK'
    Write-Host '  7) Build + install to device'
    Write-Host '  8) Open APK output folder'
    Write-Host '  9) Open project in Android Studio'
    Write-Host ' 10) Full pipeline: submodules -> build'
    Write-Host ' 11) Quick rebuild: build only'
    Write-Host '  0) Exit'
    Write-Host ''

    $choice = Read-Default 'Choose action' '10'

    switch ($choice) {
        '0' { return $false }
        '1' { Edit-ConfigInteractive $Cfg; return $true }
        '2' {
            if (-not (Read-YesNo "Patch server address in source files to $($Cfg.ServerHost):$($Cfg.ServerPort)?" $false)) {
                Write-Ok 'Skipped server patch'
                return $true
            }
            Run-StepPipeline @('patch-server')
            return $true
        }
        '3' { Run-StepPipeline @('patch-api'); return $true }
        '4' { Run-StepPipeline @('submodules'); return $true }
        '5' { Run-StepPipeline @('sdk'); return $true }
        '6' { Run-StepPipeline @('build'); return $true }
        '7' { Run-StepPipeline @('build', 'install'); return $true }
        '8' { Run-StepPipeline @('open-apk'); return $true }
        '9' { Run-StepPipeline @('open-studio'); return $true }
        '10' {
            if (-not (Read-YesNo 'Run full pipeline now?' $true)) { return $true }
            $steps = @('submodules', 'sdk', 'build')
            if ($Cfg.InstallAfterBuild) { $steps += 'install' }
            Run-StepPipeline $steps
            return $true
        }
        '11' {
            if (-not (Read-YesNo 'Run quick rebuild now?' $true)) { return $true }
            Run-StepPipeline @('sdk', 'build')
            return $true
        }
        default {
            Write-WarnMsg 'Unknown choice, try again.'
            return $true
        }
    }
}

try {
    if ($RepoRoot -notmatch 'owpengram-android-client') {
        Write-WarnMsg "Repo root: $RepoRoot"
    }

    Write-Title 'OwpenGram Android - interactive Windows build'
    Write-Host "Repository: $RepoRoot"

    $script:Cfg = Load-Config

    $script:ResolvedSdkPath = Test-Prerequisites -Cfg $script:Cfg
    Ensure-LocalProperties -SdkPath $script:ResolvedSdkPath

    if ($NoMenu) {
        Edit-ConfigInteractive $script:Cfg
        if (-not (Read-YesNo 'Run full pipeline after saving settings?' $true)) { exit 0 }
        $steps = @('submodules', 'sdk', 'build')
        if ($script:Cfg.InstallAfterBuild) { $steps += 'install' }
        Run-StepPipeline $steps
        exit 0
    }

    if (-not (Test-Path $ConfigFile)) {
        Write-WarnMsg 'No saved settings found — quick setup wizard.'
        Edit-ConfigInteractive $script:Cfg
    }

    do {
        $continue = Show-Menu
    } while ($continue)

    Write-Ok 'Done.'
}
catch {
    Write-ErrMsg $_.Exception.Message
    if ($_.ScriptStackTrace) {
        Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray
    }
    exit 1
}
