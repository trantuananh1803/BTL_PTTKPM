$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$projectRoot = $PSScriptRoot
$defaultDatabaseUrl = 'jdbc:mysql://127.0.0.1:3306/alumni_management_submission_demo?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Bangkok'

if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'This one-command setup currently supports Windows x64 only.'
}
if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    throw 'Windows LOCALAPPDATA is unavailable.'
}
$runtimeRoot = Join-Path $env:LOCALAPPDATA 'AlumniManagementSubmission\runtime'
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'tools\maven\apache-maven-3.10.0\bin\mvn.cmd'))) {
    throw 'Bundled Maven 3.10.0 was not found. Extract the complete ZIP before running setup-and-run.cmd.'
}

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null

function Get-JdkVersion {
    param([Parameter(Mandatory = $true)][string]$JavacPath)

    if (-not (Test-Path -LiteralPath $JavacPath)) { return $null }
    $output = (& $JavacPath -version 2>&1 | Out-String).Trim()
    if ($output -match 'javac\s+(?<version>\d+(?:\.\d+){0,3})') {
        $versionText = $Matches.version
        if ($versionText -notmatch '\.') { $versionText += '.0' }
        return [version]$versionText
    }
    return $null
}

function Find-UsableJdk {
    $candidatePaths = @()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidatePaths += (Join-Path $env:JAVA_HOME 'bin\javac.exe')
    }
    $systemJavac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($systemJavac -and $systemJavac.Source) { $candidatePaths += $systemJavac.Source }
    if (Test-Path -LiteralPath $runtimeRoot) {
        Get-ChildItem -LiteralPath $runtimeRoot -Directory -Filter 'jdk17-*' -ErrorAction SilentlyContinue |
            ForEach-Object { $candidatePaths += (Join-Path $_.FullName 'bin\javac.exe') }
    }

    foreach ($javacPath in ($candidatePaths | Select-Object -Unique)) {
        $version = Get-JdkVersion -JavacPath $javacPath
        if ($version -and $version.Major -ge 17) {
            $jdkBin = Split-Path -Parent $javacPath
            return [PSCustomObject]@{ Home = (Split-Path -Parent $jdkBin); Bin = $jdkBin; Version = $version }
        }
    }
    return $null
}

function Test-SupportedNodeVersion {
    param([Parameter(Mandatory = $true)][version]$Version)
    return (($Version.Major -eq 20 -and $Version.Minor -ge 19) -or
        ($Version.Major -eq 22 -and $Version.Minor -ge 12) -or
        ($Version.Major -gt 22))
}

function Find-UsableNode {
    $systemNode = Get-Command node.exe -ErrorAction SilentlyContinue
    if ($systemNode -and $systemNode.Source) {
        $versionText = (& $systemNode.Source --version 2>&1 | Out-String).Trim().TrimStart('v')
        try {
            $version = [version]$versionText
            if ((Test-SupportedNodeVersion -Version $version) -and
                (Test-Path -LiteralPath (Join-Path (Split-Path -Parent $systemNode.Source) 'npm.cmd'))) {
                return [PSCustomObject]@{ Home = (Split-Path -Parent $systemNode.Source); Version = $version }
            }
        }
        catch { }
    }

    if (Test-Path -LiteralPath $runtimeRoot) {
        foreach ($directory in (Get-ChildItem -LiteralPath $runtimeRoot -Directory -Filter 'node22-*' -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending)) {
            $nodePath = Join-Path $directory.FullName 'node.exe'
            $versionText = if (Test-Path -LiteralPath $nodePath) { (& $nodePath --version 2>&1 | Out-String).Trim().TrimStart('v') } else { '' }
            try {
                $version = [version]$versionText
                if ((Test-SupportedNodeVersion -Version $version) -and (Test-Path -LiteralPath (Join-Path $directory.FullName 'npm.cmd'))) {
                    return [PSCustomObject]@{ Home = $directory.FullName; Version = $version }
                }
            }
            catch { }
        }
    }
    return $null
}

function Remove-SetupTempDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $fullPath = [IO.Path]::GetFullPath($Path)
    $allowedPrefix = $tempRoot + 'AlumniManagementSubmission-setup-'
    if ($fullPath.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Install-VerifiedArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Sha256,
        [Parameter(Mandatory = $true)][string]$ArchiveName,
        [Parameter(Mandatory = $true)][string]$DestinationName,
        [Parameter(Mandatory = $true)][string]$ExpectedExecutable
    )

    $tempDirectory = Join-Path ([IO.Path]::GetTempPath()) ('AlumniManagementSubmission-setup-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tempDirectory -Force | Out-Null
    try {
        $archivePath = Join-Path $tempDirectory $ArchiveName
        $extractPath = Join-Path $tempDirectory 'extract'
        Write-Host "Downloading $ArchiveName..."
        $null = Invoke-WebRequest -Uri $Uri -OutFile $archivePath -UseBasicParsing -TimeoutSec 900
        $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $Sha256.ToLowerInvariant()) {
            throw "SHA-256 verification failed for $ArchiveName. The downloaded archive was rejected."
        }
        Expand-Archive -LiteralPath $archivePath -DestinationPath $extractPath -Force
        $executable = Get-ChildItem -LiteralPath $extractPath -Recurse -File -Filter $ExpectedExecutable |
            Select-Object -First 1
        if (-not $executable) { throw "The expected executable $ExpectedExecutable was not found in $ArchiveName." }
        $installHome = Split-Path -Parent $executable.FullName
        if ((Split-Path -Leaf $installHome) -eq 'bin') { $installHome = Split-Path -Parent $installHome }

        $destination = Join-Path $runtimeRoot $DestinationName
        if (Test-Path -LiteralPath $destination) {
            $destination = Join-Path $runtimeRoot ($DestinationName + '-' + [guid]::NewGuid().ToString('N'))
        }
        Move-Item -LiteralPath $installHome -Destination $destination
        return $destination
    }
    finally {
        Remove-SetupTempDirectory -Path $tempDirectory
    }
}

Write-Host 'Checking Java Development Kit...'
$jdk = Find-UsableJdk
if (-not $jdk) {
    Write-Host 'No JDK 17+ was found. Downloading a user-local JDK 17 from Eclipse Adoptium.'
    $assets = @(Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse' -TimeoutSec 60)
    $asset = $assets | Where-Object { $_.binary.package.link -and $_.binary.package.checksum } | Select-Object -First 1
    if (-not $asset) { throw 'Eclipse Adoptium did not return a JDK 17 Windows x64 archive.' }
    $jdkVersionName = [regex]::Replace([string]$asset.version_data.semver, '[^A-Za-z0-9._-]', '_')
    if ([string]::IsNullOrWhiteSpace($jdkVersionName)) { $jdkVersionName = '17-latest' }
    $jdkHome = Install-VerifiedArchive -Uri $asset.binary.package.link -Sha256 $asset.binary.package.checksum `
        -ArchiveName 'jdk17.zip' -DestinationName ("jdk17-$jdkVersionName") -ExpectedExecutable 'javac.exe'
    $jdk = [PSCustomObject]@{ Home = $jdkHome; Bin = (Join-Path $jdkHome 'bin'); Version = (Get-JdkVersion -JavacPath (Join-Path $jdkHome 'bin\javac.exe')) }
}
$env:JAVA_HOME = $jdk.Home
$env:Path = $jdk.Bin + ';' + $env:Path
Write-Host ("Using JDK {0}." -f $jdk.Version)

Write-Host 'Checking Node.js and npm...'
$node = Find-UsableNode
if (-not $node) {
    Write-Host 'No supported Node.js was found. Downloading Node.js 22 x64 from nodejs.org.'
    $baseUri = 'https://nodejs.org/dist/latest-v22.x'
    $checksumList = (Invoke-WebRequest -Uri ($baseUri + '/SHASUMS256.txt') -UseBasicParsing -TimeoutSec 60).Content
    $checksumMatch = [regex]::Match($checksumList, '(?m)^(?<sha>[0-9a-fA-F]{64})\s+node-v(?<version>22\.\d+\.\d+)-win-x64\.zip\s*$')
    if (-not $checksumMatch.Success) { throw 'Could not find the Node.js 22 Windows x64 archive checksum.' }
    $nodeVersionText = $checksumMatch.Groups['version'].Value
    $nodeArchiveName = "node-v$nodeVersionText-win-x64.zip"
    $nodeHome = Install-VerifiedArchive -Uri ($baseUri + '/' + $nodeArchiveName) -Sha256 $checksumMatch.Groups['sha'].Value `
        -ArchiveName $nodeArchiveName -DestinationName ("node22-$nodeVersionText") -ExpectedExecutable 'node.exe'
    $node = [PSCustomObject]@{ Home = $nodeHome; Version = [version]$nodeVersionText }
}
$env:Path = $node.Home + ';' + $env:Path
Write-Host ("Using Node.js {0}; npm will install frontend packages on first run." -f $node.Version)

function Test-TcpPort {
    param([Parameter(Mandatory = $true)][string]$HostName, [Parameter(Mandatory = $true)][int]$Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $result = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne(1200)) { return $false }
        $client.EndConnect($result)
        return $true
    }
    catch { return $false }
    finally { $client.Close() }
}

function Wait-TcpPort {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory = $true)][string]$HostName,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $nextUpdate = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) { throw "The $Name process stopped before port $Port became ready. Review its PowerShell window output." }
        if (Test-TcpPort -HostName $HostName -Port $Port) { return }
        if ([DateTime]::UtcNow -ge $nextUpdate) {
            Write-Host "Still waiting for $Name on port $Port..."
            $nextUpdate = [DateTime]::UtcNow.AddSeconds(15)
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for $Name on port $Port. Review its PowerShell window output."
}

$databaseUrl = if ([string]::IsNullOrWhiteSpace($env:ALUMNI_DB_URL)) { $defaultDatabaseUrl } else { $env:ALUMNI_DB_URL }
$databaseMatch = [regex]::Match($databaseUrl, '^jdbc:mysql://(?<host>\[[^\]]+\]|[^:/?]+)(?::(?<port>\d+))?/alumni_management_submission_demo(?:\?|$)')
if (-not $databaseMatch.Success) {
    throw 'ALUMNI_DB_URL must point to the dedicated alumni_management_submission_demo schema.'
}
$databaseHost = [regex]::Replace($databaseMatch.Groups['host'].Value, '[\[\]]', '')
$databasePortText = $databaseMatch.Groups['port'].Value
$databasePort = if ($databasePortText) { [int]$databasePortText } else { 3306 }
if (-not (Test-TcpPort -HostName $databaseHost -Port $databasePort)) {
    throw "MySQL is not reachable at ${databaseHost}:$databasePort. Install and start MySQL, create the demo schema/account as described in README.md, then run this command again."
}

foreach ($appPort in @(8080, 5173)) {
    if (Test-TcpPort -HostName '127.0.0.1' -Port $appPort) {
        throw "Port $appPort is already in use. Stop the other application instance before running setup-and-run.cmd."
    }
}

$backendScript = Join-Path $projectRoot 'run-backend.ps1'
$frontendScript = Join-Path $projectRoot 'run-frontend.ps1'
$powershellCommand = Get-Command powershell.exe -ErrorAction SilentlyContinue
if (-not $powershellCommand -or -not $powershellCommand.Source) {
    throw 'Windows PowerShell (powershell.exe) was not found.'
}
$powershellExe = $powershellCommand.Source
Write-Host 'Starting backend in a separate PowerShell window...'
$backendProcess = Start-Process -FilePath $powershellExe `
    -ArgumentList @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $backendScript + '"')) `
    -WorkingDirectory $projectRoot -PassThru
Wait-TcpPort -Process $backendProcess -HostName '127.0.0.1' -Port 8080 -TimeoutSeconds 900 -Name 'backend'

Write-Host 'Starting frontend in a separate PowerShell window...'
$frontendProcess = Start-Process -FilePath $powershellExe `
    -ArgumentList @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $frontendScript + '"')) `
    -WorkingDirectory $projectRoot -PassThru
Wait-TcpPort -Process $frontendProcess -HostName '127.0.0.1' -Port 5173 -TimeoutSeconds 600 -Name 'frontend'

Write-Host 'Both services are ready. Opening http://127.0.0.1:5173'
Start-Process 'http://127.0.0.1:5173'
Write-Host 'Keep the backend and frontend PowerShell windows open. Press Ctrl+C in each window to stop that service.'
