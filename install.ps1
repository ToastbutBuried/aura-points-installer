# Aura Points server installer - Windows.
#
#   irm https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main/install.ps1 | iex
#
# (or double-click install-windows.bat). Finds a Java 17+ (yours, Prism's, the Minecraft Launcher's or
# the Modrinth App's - or downloads one into a temp folder), then runs aura-installer.jar, which asks for
# a name and which mods, finds your launcher and makes the instance.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$raw = 'https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main'
$work = Join-Path ([IO.Path]::GetTempPath()) ('aura-install-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $work | Out-Null

# major version of a java.exe (8 for "1.8.0", 21 for "21.0.7"), 0 if it doesn't run
function Get-JavaMajor([string]$exe) {
	try {
		$err = Join-Path $work 'v.txt'
		Start-Process -FilePath $exe -ArgumentList '-version' -NoNewWindow -Wait -RedirectStandardError $err | Out-Null
		$text = Get-Content $err -Raw
		if ($text -match 'version "(\d+)(?:\.(\d+))?') {
			$m = [int]$Matches[1]
			if ($m -eq 1 -and $Matches[2]) { $m = [int]$Matches[2] }
			return $m
		}
	} catch {}
	return 0
}

function Find-Java {
	$patterns = @(
		"$env:JAVA_HOME\bin\java.exe",
		"$env:APPDATA\PrismLauncher\java\*\bin\java.exe",
		"$env:USERPROFILE\scoop\persist\prismlauncher\java\*\bin\java.exe",
		"${env:ProgramFiles(x86)}\Minecraft Launcher\runtime\java-runtime-*\windows-x64\java-runtime-*\bin\java.exe",
		"$env:LOCALAPPDATA\Packages\Microsoft.4297127D64EC6_*\LocalCache\Local\runtime\java-runtime-*\windows-x64\java-runtime-*\bin\java.exe",
		"$env:APPDATA\.minecraft\runtime\java-runtime-*\windows-x64\java-runtime-*\bin\java.exe",
		"$env:APPDATA\ModrinthApp\meta\java_versions\*\bin\java.exe",
		"$env:ProgramFiles\Eclipse Adoptium\*\bin\java.exe",
		"$env:ProgramFiles\Java\*\bin\java.exe",
		"$env:ProgramFiles\Microsoft\*\bin\java.exe",
		"$env:ProgramFiles\Zulu\*\bin\java.exe"
	)
	$found = @()
	$onPath = Get-Command java.exe -ErrorAction SilentlyContinue
	if ($onPath) { $found += $onPath.Source }
	foreach ($p in $patterns) {
		if ($p.StartsWith('\')) { continue } # JAVA_HOME not set
		Resolve-Path -Path $p -ErrorAction SilentlyContinue | ForEach-Object { $found += $_.Path }
	}
	foreach ($j in $found) {
		if ((Get-JavaMajor $j) -ge 17) { return $j }
	}
	return $null
}

try {
	Write-Host 'Looking for Java ...'
	$java = Find-Java
	if (-not $java) {
		Write-Host 'No Java 17+ found - downloading a temporary one (Eclipse Temurin 21, deleted afterwards) ...'
		$arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'aarch64' } else { 'x64' }
		$zip = Join-Path $work 'jre.zip'
		Invoke-WebRequest -UseBasicParsing -Uri "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$arch/jre/hotspot/normal/eclipse" -OutFile $zip
		Expand-Archive -Path $zip -DestinationPath (Join-Path $work 'jre')
		$java = (Get-ChildItem -Path (Join-Path $work 'jre') -Recurse -Filter java.exe | Select-Object -First 1).FullName
		if (-not $java) { throw 'Could not get Java. Install Java 21 from https://adoptium.net and run this again.' }
	}
	Write-Host "Using $java"

	$jar = Join-Path $work 'aura-installer.jar'
	Invoke-WebRequest -UseBasicParsing -Uri "$raw/aura-installer.jar" -OutFile $jar
	& $java -jar $jar @args
} catch {
	Write-Host "Something went wrong: $($_.Exception.Message)" -ForegroundColor Red
	Read-Host 'Press Enter to close'
} finally {
	Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
