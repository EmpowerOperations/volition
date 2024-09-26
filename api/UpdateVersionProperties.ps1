Param(
    [string]
    [Parameter(Mandatory=$true)]
    $versionString
)

# TODO: this was removed from build.gradle
# because its wierd that gradle would fiddle with dotnet's bits.
# make this a dotnet build task, that reads from a shared file

$pathToEdit = "./Properties/AssemblyInfo.cs"

$assemblyInfoCsContent = Get-Content $pathToEdit

# following https://adamtheautomator.com/powershell-replace/
# PS> $string -replace '(.*), (.*)','$2,$1'
# content is '[assembly: AssemblyFileVersion("1.3.0.310")]''
$regex = '(?<header>\[assembly: +Assembly(File)?Version *\(\")(?<version>[0-9.]+)(?<trailer>\"\)\])'
$updatedContent = $assemblyInfoCsContent -replace $regex,"`${header}$versionString`${trailer}"

Set-Content -Path $pathToEdit $updatedContent