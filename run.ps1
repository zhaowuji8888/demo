param([switch]$Test, [int]$Port=8080)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    $compiler=(Get-Command javac -ErrorAction Stop).Source
    $jdk=Split-Path (Split-Path $compiler)
    $jar=$env:DERBY_JAR
    if (!$jar) { $jar=Join-Path $jdk 'db\lib\derby.jar' }
    if (!(Test-Path -LiteralPath $jar)) { throw 'Set DERBY_JAR to the path of derby.jar (Derby 10.14 with Java 8).' }
    New-Item -Path out -ItemType Directory -Force | Out-Null
    $sources=@(Get-ChildItem src\rooms\*.java | ForEach-Object FullName)
    if ($Test) { $sources+= @(Get-ChildItem test\rooms\*.java | ForEach-Object FullName) }
    & $compiler -encoding UTF-8 -cp $jar -d out $sources
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    $entry=if($Test){'rooms.StoreTest'}else{'rooms.Server'}
    & (Join-Path $jdk 'bin\java.exe') -cp "out;$jar" $entry $Port
    if ($LASTEXITCODE -ne 0) { throw 'Java exited with an error' }
} finally { Pop-Location }
