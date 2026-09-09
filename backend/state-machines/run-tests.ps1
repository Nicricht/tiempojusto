$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Out = Join-Path $Root "build/classes"
if (Test-Path (Join-Path $Root "build")) { Remove-Item -Recurse -Force (Join-Path $Root "build") }
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$files = Get-ChildItem -Recurse -Path (Join-Path $Root "src/main/java"),(Join-Path $Root "src/test/java") -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 21 -d $Out $files
& java -ea -cp $Out cl.tiempojusto.statemachine.StateMachineContractTests
