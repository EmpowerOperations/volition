Param(
    [System.IO.FileInfo] $VersionsFile = "versions.properties",
    [System.IO.FileInfo] $AssemblyInfoFile = "Properties/AssemblyInfo.cs"
)

$versionsTable = ConvertFrom-StringData (Get-Content -Raw $VersionsFile)
$formalVolitionVersion = $versionsTable["volition"]
If($env:VOLITION_BUILD_NUMBER)
    { $buildNumber = "$([int] $env:VOLITION_BUILD_NUMBER)" } Else
    { $buildNumber = "0" }

$versionString = "$formalVolitionVersion.$buildNumber"

$assemblyInfoCsContent = Get-Content $AssemblyInfoFile

# following https://adamtheautomator.com/powershell-replace/
# PS> $string -replace '(.*), (.*)','$2,$1'
# content is '[assembly: AssemblyFileVersion("1.3.0.310")]''
$regex = '(?<header>\[assembly: +Assembly(File)?Version *\(\")(?<version>[0-9.]+)(?<trailer>\"\)\])'
$updatedContent = $assemblyInfoCsContent -replace $regex,"`${header}$versionString`${trailer}"

Write-Output $updatedContent | Write-Verbose

Set-Content -Path $AssemblyInfoFile $updatedContent