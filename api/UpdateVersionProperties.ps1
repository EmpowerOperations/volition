Param(
    [string]
    [Parameter(Mandatory=$true)]
    $versionString
)

$pathToEdit = "./Properties/AssemblyInfo.cs"

$assemblyInfoCsContent = Get-Content $pathToEdit

# following https://adamtheautomator.com/powershell-replace/
# PS> $string -replace '(.*), (.*)','$2,$1'
# content is '[assembly: AssemblyFileVersion("1.3.0.310")]''
$regex = '(?<header>\[assembly: +Assembly(File)?Version *\(\")(?<version>[0-9.]+)(?<trailer>\"\)\])'
$updatedContent = $assemblyInfoCsContent -replace $regex,"`${header}$versionString`${trailer}"

Set-Content -Path $pathToEdit $updatedContent