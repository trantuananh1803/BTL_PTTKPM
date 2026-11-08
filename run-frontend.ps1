$ErrorActionPreference = 'Stop'
$frontend = Join-Path $PSScriptRoot 'frontend'

if (-not (Get-Command node -ErrorAction SilentlyContinue) -or -not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw 'Install Node.js 20.19+ or 22.12+ with npm, then reopen PowerShell.'
}

$versionText = (& node --version).Trim().TrimStart('v')
try {
    $nodeVersion = [version]$versionText
}
catch {
    throw 'Could not determine the installed Node.js version.'
}
$supported = ($nodeVersion.Major -eq 20 -and $nodeVersion.Minor -ge 19) -or
    ($nodeVersion.Major -eq 22 -and $nodeVersion.Minor -ge 12) -or
    ($nodeVersion.Major -gt 22)
if (-not $supported) {
    throw "Node.js $versionText is not supported. Install Node.js 20.19+ or 22.12+."
}

Push-Location $frontend
try {
    if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules\vite\bin\vite.js'))) {
        npm ci
        if ($LASTEXITCODE -ne 0) {
            throw "npm ci stopped with exit code $LASTEXITCODE."
        }
    }
    npm run dev -- --host 127.0.0.1
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend stopped with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
