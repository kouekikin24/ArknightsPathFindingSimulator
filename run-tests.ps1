$ErrorActionPreference = "Stop"

if (Test-Path -LiteralPath "out") {
    [System.IO.Directory]::Delete((Resolve-Path -LiteralPath "out").Path, $true)
}

$sourceFiles = Get-ChildItem -Recurse -File src/main, src/test -Filter *.java |
    ForEach-Object { $_.FullName }

New-Item -ItemType Directory -Force out | Out-Null
javac --release 21 -d out $sourceFiles
java -ea -cp out SimulatorTests
