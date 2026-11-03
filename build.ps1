# Compile Market Regime contre l'API de l'installation Bookmap locale et TWS API locale.
# Usage : powershell -ExecutionPolicy Bypass -File build.ps1 [-Version 1.0.1] [-User BrunoF] [-BookmapLib 'C:\Program Files\Bookmap\lib'] [-TwsApiDir 'C:\Users\superpc\tws-api\source\javaclient']
param(
    [string]$Version = '1.0.1',
    [string]$User = 'BrunoF',
    [string]$BookmapLib = 'C:\Program Files\Bookmap\lib',
    [string]$TwsApiDir = 'C:\Users\superpc\tws-api\source\javaclient'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$Name = "${User}_MarketRegime_${Version}.jar"
$twsJar = Join-Path $TwsApiDir 'TwsApi.jar'
$protoJar = Join-Path $TwsApiDir 'jars\protobuf-java-4.29.5.jar'
$cp = "$BookmapLib\bm-l1api.jar;$BookmapLib\bm-simplified-api-wrapper.jar;$twsJar;$protoJar"
$out = Join-Path $root 'build\classes'

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'javac introuvable : installe un JDK (17 ou plus) et rouvre le terminal.'
}
if (-not (Test-Path "$BookmapLib\bm-l1api.jar")) {
    throw "API Bookmap introuvable dans $BookmapLib : indique le bon dossier avec -BookmapLib."
}
if (-not (Test-Path $twsJar)) {
    throw "TwsApi.jar introuvable dans $TwsApiDir : indique le bon dossier avec -TwsApiDir."
}

New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem (Join-Path $out 'com\marketregime') -Recurse -Filter *.class -ErrorAction SilentlyContinue | ForEach-Object {
    try { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction Stop } catch { }
}

$jar = (Get-Command jar -ErrorAction SilentlyContinue).Source
if (-not $jar) {
    $javaHome = (cmd /c 'java -XshowSettings:properties -version 2>&1' | Select-String 'java\.home = (.+)$').Matches[0].Groups[1].Value.Trim()
    $jar = Join-Path $javaHome 'bin\jar.exe'
}
if (-not (Test-Path $jar)) { throw "jar.exe introuvable ($jar)." }

# Extrait TwsApi.jar et protobuf dans build\classes une seule fois s'ils n'y sont pas encore
if (-not (Test-Path (Join-Path $out 'com\ib\client\EClientSocket.class'))) {
    Push-Location $out
    try {
        & $jar -xf $twsJar com
        if (Test-Path $protoJar) {
            & $jar -xf $protoJar com
        }
    } finally {
        Pop-Location
    }
}

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac --release 17 -encoding UTF-8 -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation échouée.' }

Set-Content -LiteralPath (Join-Path $out 'com\marketregime\version.txt') -Value $Version -NoNewline -Encoding ascii

$marker = Join-Path $out 'bm-strategy-package-fs-root.jar'
New-Item -ItemType File -Force $marker | Out-Null

$tmpJar = Join-Path $out $Name
& $jar --create --file $tmpJar -C $out com
if ($LASTEXITCODE -ne 0) { throw 'Création du jar échouée.' }
$targetJar = Join-Path $root $Name
try {
    Copy-Item -LiteralPath $tmpJar -Destination $targetJar -Force -ErrorAction Stop
    Remove-Item -LiteralPath $tmpJar -Force -ErrorAction SilentlyContinue
    Write-Host "OK -> $targetJar"
} catch {
    Write-Host "Classes mises à jour dans $out (ferme ou retire $Name dans Bookmap pour écraser $targetJar)"
}
Write-Host "Rechargement rapide -> $marker"
