# Copy app-owned resources from a JADX dump into app/src/main/res.
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Root = Split-Path -Parent $PSScriptRoot
$RJava = Join-Path $Root "decompiled\sources\ru\mybudget\app\C1788R.java"
$ResSrc = Join-Path $Root "decompiled\resources\res"
$ResDst = Join-Path $Root "app\src\main\res"

$FileTypes = @("layout","drawable","mipmap","xml","color","anim","animator","menu","font","raw","interpolator")
$SkipValueFiles = @("public.xml","attrs.xml","ids.xml")

function Parse-AppResources([string]$path) {
    $map = @{}
    $current = $null
    $collecting = $false
    Get-Content -LiteralPath $path -Encoding UTF8 | ForEach-Object {
        $line = $_
        if ($line -match 'public static final class (\w+) \{') {
            $current = $Matches[1]
            if (-not $map.ContainsKey($current)) { $map[$current] = New-Object System.Collections.Generic.HashSet[string] }
            $collecting = $true
            return
        }
        if (-not $current -or -not $collecting) { return }
        if ($line -match 'JADX INFO: Added by JADX') {
            $collecting = $false
            return
        }
        if ($line -match 'public static(?: final)? int (\w+) = ') {
            [void]$map[$current].Add($Matches[1])
        }
    }
    $map
}

function Keep-Resource($tag, $name, $map) {
    if ([string]::IsNullOrEmpty($name)) { return $false }
    switch ($tag) {
        "style" {
            $java = $name.Replace(".", "_")
            return ($map.ContainsKey("style") -and ($map["style"].Contains($java) -or $map["style"].Contains($name)))
        }
        "string" { $t = "string" }
        "color" { $t = "color" }
        "dimen" { $t = "dimen" }
        "plurals" { $t = "plurals" }
        "integer" { $t = "integer" }
        "bool" { $t = "bool" }
        "array" { $t = "array" }
        "string-array" { $t = "array" }
        "integer-array" { $t = "array" }
        default { return $false }
    }
    return ($map.ContainsKey($t) -and $map[$t].Contains($name))
}

function Save-Utf8Xml($xml, $dest) {
    $dir = Split-Path $dest
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $settings = New-Object System.Xml.XmlWriterSettings
    $settings.Encoding = New-Object System.Text.UTF8Encoding($false)
    $settings.Indent = $true
    $settings.OmitXmlDeclaration = $false
    $writer = [System.Xml.XmlWriter]::Create($dest, $settings)
    try { $xml.Save($writer) } finally { $writer.Dispose() }
}

$map = Parse-AppResources $RJava
Write-Host "app resource types:"
$map.Keys | Sort-Object | ForEach-Object { Write-Host ("  {0}: {1}" -f $_, $map[$_].Count) }

if (Test-Path $ResDst) { Remove-Item -Recurse -Force $ResDst }
New-Item -ItemType Directory -Path $ResDst | Out-Null

$script:copied = 0
Get-ChildItem -LiteralPath $ResSrc -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($ResSrc.Length).TrimStart("\","/")
    $folder = $rel.Split("\")[0]
    $kind = $folder.Split("-")[0]
    if ($FileTypes -notcontains $kind) { return }
    if (-not $map.ContainsKey($kind)) { return }
    $stem = [IO.Path]::GetFileNameWithoutExtension($_.Name)
    if (-not $map[$kind].Contains($stem)) { return }
    $dest = Join-Path $ResDst $rel
    $destDir = Split-Path $dest
    if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir | Out-Null }
    Copy-Item -LiteralPath $_.FullName -Destination $dest -Force
    $script:copied++
}
Write-Host "copied file resources: $($script:copied)"

$skipColors = New-Object System.Collections.Generic.HashSet[string]
Get-ChildItem -LiteralPath $ResDst -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
    $_.Directory.Name -like "color*"
} | ForEach-Object { [void]$skipColors.Add($_.BaseName) }

$script:valueFiles = 0
$script:valueItems = 0
Get-ChildItem -LiteralPath $ResSrc -Directory | Where-Object { $_.Name.StartsWith("values") } | ForEach-Object {
    Get-ChildItem -LiteralPath $_.FullName -Filter "*.xml" | ForEach-Object {
        if ($SkipValueFiles -contains $_.Name) { return }
        $xml = New-Object System.Xml.XmlDocument
        $xml.PreserveWhitespace = $false
        $xml.Load($_.FullName)
        if ($null -eq $xml.DocumentElement -or $xml.DocumentElement.Name -ne "resources") { return }
        $kept = 0
        $toRemove = @()
        foreach ($child in @($xml.DocumentElement.ChildNodes)) {
            if ($child.NodeType -ne [System.Xml.XmlNodeType]::Element) {
                $toRemove += $child
                continue
            }
            $tag = $child.LocalName
            $name = $child.GetAttribute("name")
            if ($tag -eq "color" -and $skipColors.Contains($name)) {
                $toRemove += $child
                continue
            }
            if (Keep-Resource $tag $name $map) {
                $kept++
            } else {
                $toRemove += $child
            }
        }
        foreach ($n in $toRemove) { [void]$xml.DocumentElement.RemoveChild($n) }
        if ($kept -eq 0) { return }
        $rel = $_.FullName.Substring($ResSrc.Length).TrimStart("\","/")
        $dest = Join-Path $ResDst $rel
        Save-Utf8Xml $xml $dest
        $script:valueFiles++
        $script:valueItems += $kept
    }
}

Write-Host "filtered values files: $($script:valueFiles) ($($script:valueItems) items)"
Write-Host "output: $ResDst"
