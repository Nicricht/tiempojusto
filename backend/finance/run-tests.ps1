$ErrorActionPreference = 'Stop'
if (Test-Path out) { Remove-Item -Recurse -Force out }
New-Item -ItemType Directory -Path out | Out-Null
$files = Get-ChildItem -Recurse src/main/java,src/test/java -Filter *.java | ForEach-Object { $_.FullName }
javac --release 21 -d out $files
java -cp out cl.tiempojusto.finance.FinanceContractTests
