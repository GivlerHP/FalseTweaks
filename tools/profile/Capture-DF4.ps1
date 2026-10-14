param(
    [int]$Seconds = 120,
    [int]$TargetProcessId = 0,
    [string]$Jdk = 'C:\Program Files\Java\jdk1.8.0_202'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ($Seconds -lt 1 -or $Seconds -gt 600) { throw 'Seconds must be between 1 and 600.' }
if ($TargetProcessId -eq 0) {
    $games = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'instances[\\/]DF4([\\/]|\s|"|$)'
    })
    if ($games.Count -ne 1) { throw 'Start DF4 first, or provide -TargetProcessId. Exactly one DF4 process is required.' }
    $TargetProcessId = $games[0].ProcessId
}
$classes = Join-Path $project 'build\profiler-classes'
$output = Join-Path $project ('build\profiles\DF4-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$toolsJar = Join-Path $Jdk 'lib\tools.jar'
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $toolsJar -d $classes (Join-Path $PSScriptRoot 'FrameSampler.java')
if ($LASTEXITCODE -ne 0) { throw 'Profiler compilation failed.' }
Write-Host "Sampling DF4 process $TargetProcessId for $Seconds seconds. Walk the benchmark route now."
& (Join-Path $Jdk 'bin\java.exe') -cp "$classes;$toolsJar" FrameSampler "$TargetProcessId" "$Seconds" $output
if ($LASTEXITCODE -ne 0) { throw 'Recording failed. Keep DF4 running until capture completes.' }
Write-Host "Results: $output"
