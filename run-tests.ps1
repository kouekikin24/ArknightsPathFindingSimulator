$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

if (Test-Path -LiteralPath "out") {
    [System.IO.Directory]::Delete((Resolve-Path -LiteralPath "out").Path, $true)
}

$sourceFiles = Get-ChildItem -Recurse -File src/main, src/test -Filter *.java |
    ForEach-Object { $_.FullName }

New-Item -ItemType Directory -Force out | Out-Null
& javac --release 21 -encoding UTF-8 -d out $sourceFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& java '-Dfile.encoding=UTF-8' -cp out SimulatorTests $args
exit $LASTEXITCODE
