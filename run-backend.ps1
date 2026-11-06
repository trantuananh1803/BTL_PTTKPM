$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$backend = Join-Path $root 'backend'
$maven = Join-Path $root 'tools\maven\apache-maven-3.10.0\bin\mvn.cmd'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'JDK 17 or newer is required. Install it and reopen PowerShell.'
}
if (-not (Test-Path -LiteralPath $maven)) {
    throw 'Bundled Maven was not found in tools\maven.'
}
if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    throw 'Windows LOCALAPPDATA is unavailable.'
}

$credentialPath = Join-Path $env:LOCALAPPDATA 'AlumniManagementSubmission\mysql-credential.xml'
$credentialDirectory = Split-Path -Parent $credentialPath
$mavenRepository = Join-Path $env:LOCALAPPDATA 'AlumniManagementSubmission\maven-repository'
if (-not (Test-Path -LiteralPath $credentialPath)) {
    New-Item -ItemType Directory -Path $credentialDirectory -Force | Out-Null
    $username = Read-Host 'MySQL user with access to alumni_management_submission_demo'
    if ([string]::IsNullOrWhiteSpace($username)) {
        throw 'MySQL username cannot be empty.'
    }
    $securePassword = Read-Host 'MySQL password (saved encrypted for this Windows account)' -AsSecureString
    $credential = [System.Management.Automation.PSCredential]::new($username.Trim(), $securePassword)
    $credential | Export-Clixml -LiteralPath $credentialPath
}

$mysqlCredential = Import-Clixml -LiteralPath $credentialPath
$passwordBstr = [IntPtr]::Zero
$storage = Join-Path $backend 'var\private-files'
New-Item -ItemType Directory -Path $storage -Force | Out-Null

$defaultUrl = 'jdbc:mysql://127.0.0.1:3306/alumni_management_submission_demo?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Bangkok'
$databaseUrl = if ([string]::IsNullOrWhiteSpace($env:ALUMNI_DB_URL)) { $defaultUrl } else { $env:ALUMNI_DB_URL }
if ($databaseUrl -notmatch '/alumni_management_submission_demo(?:\?|$)') {
    throw 'ALUMNI_DB_URL must point to the dedicated alumni_management_submission_demo schema.'
}

$env:DB_URL = $databaseUrl
$env:DB_USERNAME = $mysqlCredential.UserName
$env:SERVER_PORT = '8080'
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:DB_MIGRATE_ENABLED = 'true'
$env:BOOTSTRAP_ADMIN_ENABLED = 'false'
$env:DEMO_ACCOUNTS_ENABLED = 'true'
$env:ALUMNI_PRIVATE_STORAGE = $storage

try {
    $passwordBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($mysqlCredential.Password)
    $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordBstr)
    Push-Location $backend
    try {
        New-Item -ItemType Directory -Path $mavenRepository -Force | Out-Null
        & $maven "-Dmaven.repo.local=$mavenRepository" spring-boot:run
        if ($LASTEXITCODE -ne 0) {
            throw "Backend stopped with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($passwordBstr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordBstr)
    }
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:DB_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:DB_URL -ErrorAction SilentlyContinue
    foreach ($name in @('SERVER_PORT', 'SPRING_PROFILES_ACTIVE', 'DB_MIGRATE_ENABLED', 'BOOTSTRAP_ADMIN_ENABLED', 'DEMO_ACCOUNTS_ENABLED', 'ALUMNI_PRIVATE_STORAGE')) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
