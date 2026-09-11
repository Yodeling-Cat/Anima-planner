# Runs bin/deploy, which is a bash script, from PowerShell.
#
#   .\bin\deploy.ps1
#   .\bin\deploy.ps1 --public
#
# Git Bash ships with Git for Windows and is what the repository's other shell tooling
# assumes, so it is looked for by path before anything on PATH is tried.
$ErrorActionPreference = 'Stop'

$bash = @(
    "$env:ProgramFiles\Git\bin\bash.exe",
    "${env:ProgramFiles(x86)}\Git\bin\bash.exe",
    "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $bash) { $bash = (Get-Command bash -ErrorAction SilentlyContinue).Source }
if (-not $bash) { throw "Could not find bash. Install Git for Windows, or run bin/deploy from a bash shell." }

# stderr is merged into stdout by bash rather than by PowerShell. Docker writes its build
# progress to stderr, and Windows PowerShell turns each native stderr line it is asked to
# redirect into a NativeCommandError record — pages of red about a build that is going
# fine. Merging on the bash side means PowerShell only ever sees one stream, so
# `.\bin\deploy.ps1 | Tee-Object -FilePath deploy.log` captures everything and reads as
# the deploy actually went.
$script = "$PSScriptRoot/deploy" -replace '\\', '/'
& $bash -c 'exec "$0" "$@" 2>&1' $script @args
exit $LASTEXITCODE
