[CmdletBinding()]
param(
    [string]$ProjectDirectory = (Split-Path -Parent $PSScriptRoot),
    [switch]$DryRun,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function Read-GradleProperties {
    param([string]$Path)

    $result = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#') -or -not $trimmed.Contains('=')) {
            continue
        }

        $separator = $trimmed.IndexOf('=')
        $key = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        $result[$key] = $value
    }
    return $result
}

function Unprotect-SecretValue {
    param(
        [object]$EncryptedValue,
        [byte[]]$Key
    )

    $aes = [Security.Cryptography.Aes]::Create()
    try {
        $aes.KeySize = 256
        $aes.Key = $Key
        $aes.IV = [Convert]::FromBase64String([string]$EncryptedValue.iv)
        $decryptor = $aes.CreateDecryptor()
        try {
            $cipherBytes = [Convert]::FromBase64String([string]$EncryptedValue.ciphertext)
            $plainBytes = $decryptor.TransformFinalBlock($cipherBytes, 0, $cipherBytes.Length)
            return [Text.Encoding]::UTF8.GetString($plainBytes)
        }
        finally {
            $decryptor.Dispose()
        }
    }
    finally {
        $aes.Dispose()
    }
}

function Get-Sha256Hex {
    param([string]$Path)

    $sha256 = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($Path)
    try {
        $hashBytes = $sha256.ComputeHash($stream)
        return ($hashBytes | ForEach-Object { $_.ToString('X2') }) -join ''
    }
    finally {
        $stream.Dispose()
        $sha256.Dispose()
    }
}

function Test-WebhookUrl {
    param([string]$Url)

    if ($Url -notmatch '^https://(?:canary\.|ptb\.)?discord(?:app)?\.com/api/webhooks/[0-9]+/[A-Za-z0-9._-]+$') {
        throw 'The encrypted configuration contains an invalid Discord webhook URL.'
    }
}

function Get-RetryMilliseconds {
    param([string]$ResponseBody)

    try {
        $retry = ($ResponseBody | ConvertFrom-Json).retry_after
        if ($null -ne $retry) {
            return [Math]::Max(500, [int][Math]::Ceiling([double]$retry * 1000.0))
        }
    }
    catch {
        # Fall through to a conservative retry interval.
    }
    return 2000
}

function Invoke-DiscordJson {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$WebhookUrl,
        [Collections.IDictionary]$Payload
    )

    $json = $Payload | ConvertTo-Json -Depth 10 -Compress
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        $content = [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
        try {
            $response = $Client.PostAsync("$WebhookUrl`?wait=true", $content).GetAwaiter().GetResult()
            $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ([int]$response.StatusCode -eq 429 -and $attempt -lt 4) {
                Start-Sleep -Milliseconds (Get-RetryMilliseconds $responseBody)
                continue
            }
            if (-not $response.IsSuccessStatusCode) {
                throw "Discord returned HTTP $([int]$response.StatusCode): $responseBody"
            }
            return $responseBody
        }
        finally {
            $content.Dispose()
        }
    }
}

function Invoke-DiscordFile {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$WebhookUrl,
        [Collections.IDictionary]$Payload,
        [string]$FilePath
    )

    $json = $Payload | ConvertTo-Json -Depth 10 -Compress
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        $multipart = [Net.Http.MultipartFormDataContent]::new()
        $payloadContent = [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
        $stream = [IO.File]::OpenRead($FilePath)
        $fileContent = [Net.Http.StreamContent]::new($stream)
        $fileContent.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('application/java-archive')

        $multipart.Add($payloadContent, 'payload_json')
        $multipart.Add($fileContent, 'files[0]', [IO.Path]::GetFileName($FilePath))

        try {
            $response = $Client.PostAsync("$WebhookUrl`?wait=true", $multipart).GetAwaiter().GetResult()
            $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ([int]$response.StatusCode -eq 429 -and $attempt -lt 4) {
                Start-Sleep -Milliseconds (Get-RetryMilliseconds $responseBody)
                continue
            }
            if (-not $response.IsSuccessStatusCode) {
                throw "Discord returned HTTP $([int]$response.StatusCode): $responseBody"
            }
            return $responseBody
        }
        finally {
            $multipart.Dispose()
        }
    }
}

function Split-DiscordText {
    param(
        [string]$Text,
        [int]$MaximumLength = 1800
    )

    $parts = [Collections.Generic.List[string]]::new()
    $current = [Text.StringBuilder]::new()

    foreach ($originalLine in ($Text -split "`r?`n")) {
        $remaining = $originalLine
        do {
            $line = if ($remaining.Length -gt $MaximumLength) {
                $piece = $remaining.Substring(0, $MaximumLength)
                $remaining = $remaining.Substring($MaximumLength)
                $piece
            }
            else {
                $piece = $remaining
                $remaining = ''
                $piece
            }

            $extraLength = $line.Length + $(if ($current.Length -gt 0) { 1 } else { 0 })
            if ($current.Length -gt 0 -and $current.Length + $extraLength -gt $MaximumLength) {
                $parts.Add($current.ToString())
                $null = $current.Clear()
            }
            if ($current.Length -gt 0) {
                $null = $current.Append("`n")
            }
            $null = $current.Append($line)
        } while ($remaining.Length -gt 0)
    }

    if ($current.Length -gt 0) {
        $parts.Add($current.ToString())
    }
    return $parts.ToArray()
}

function Save-PublishState {
    param(
        [string]$Path,
        [Collections.IDictionary]$State
    )

    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) {
        $null = New-Item -ItemType Directory -Path $directory
    }
    $State | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Path -Encoding UTF8
}

$projectPath = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$propertiesPath = Join-Path $projectPath 'gradle.properties'
$changelogPath = Join-Path $projectPath 'CHANGELOG.md'
$configurationPath = Join-Path $projectPath '.discord-release-secrets.json'
$keyPath = Join-Path $projectPath '.discord-release-key.bin'

foreach ($requiredPath in @($propertiesPath, $changelogPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required file is missing: $requiredPath"
    }
}

$properties = Read-GradleProperties $propertiesPath
foreach ($requiredProperty in @('mod_id', 'mod_name', 'mod_version', 'minecraft_version', 'neo_version')) {
    if (-not $properties.ContainsKey($requiredProperty)) {
        throw "Missing Gradle property: $requiredProperty"
    }
}

$version = $properties.mod_version
$modName = $properties.mod_name
$jarPath = Join-Path $projectPath "build\libs\$($properties.mod_id)-$version.jar"
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Expected release JAR is missing: $jarPath"
}

$jarHash = Get-Sha256Hex $jarPath
$changelog = [IO.File]::ReadAllText($changelogPath, [Text.Encoding]::UTF8)
$escapedVersion = [Regex]::Escape($version)
$sectionMatch = [Regex]::Match(
    $changelog,
    "(?ms)^##\s+$escapedVersion(?:\s+-[^\r\n]*)?\s*\r?\n(?<body>.*?)(?=^##\s+|\z)"
)
if (-not $sectionMatch.Success) {
    throw "CHANGELOG.md has no section beginning with '## $version'."
}

$sectionBody = $sectionMatch.Groups['body'].Value.Trim()
$changelogMessage = "# $modName $version - Changelog`n`n$sectionBody"
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $changelogHashBytes = $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($changelogMessage))
    $changelogHash = ($changelogHashBytes | ForEach-Object { $_.ToString('X2') }) -join ''
}
finally {
    $sha256.Dispose()
}
$changelogParts = @(Split-DiscordText $changelogMessage)

$bulletMatches = [Regex]::Matches($sectionBody, '(?m)^-\s+(.+)$')
$highlights = [Collections.Generic.List[string]]::new()
for ($index = 0; $index -lt [Math]::Min(6, $bulletMatches.Count); $index++) {
    $text = $bulletMatches[$index].Groups[1].Value.Trim()
    if ($text.Length -gt 170) {
        $text = $text.Substring(0, 167) + '...'
    }
    $highlights.Add("- $text")
}

$releaseLines = [Collections.Generic.List[string]]::new()
$releaseLines.Add("# $modName $version")
$releaseLines.Add('')
$releaseLines.Add("**Minecraft:** $($properties.minecraft_version)")
$releaseLines.Add("**Loader:** NeoForge $($properties.neo_version)")
$releaseLines.Add('**Download:** attached JAR')
$releaseLines.Add('')
if ($highlights.Count -gt 0) {
    $releaseLines.Add('## Highlights')
    foreach ($highlight in $highlights) {
        $releaseLines.Add($highlight)
    }
    $releaseLines.Add('')
}
$releaseLines.Add('The complete notes were posted in the changelog channel.')
$releaseLines.Add('')
$releaseLines.Add("**SHA-256:** ``$jarHash``")
$releaseMessage = $releaseLines -join "`n"
if ($releaseMessage.Length -gt 1950) {
    throw "Generated release message is too long ($($releaseMessage.Length) characters)."
}

Write-Host ''
Write-Host "Release: $modName $version" -ForegroundColor Cyan
Write-Host "JAR: $jarPath"
Write-Host "SHA-256: $jarHash"
Write-Host "Changelog messages: $($changelogParts.Count)"

if ($DryRun) {
    Write-Host ''
    Write-Host '--- Release preview ---' -ForegroundColor Yellow
    Write-Host $releaseMessage
    for ($index = 0; $index -lt $changelogParts.Count; $index++) {
        Write-Host ''
        Write-Host "--- Changelog preview $($index + 1)/$($changelogParts.Count) ---" -ForegroundColor Yellow
        Write-Host $changelogParts[$index]
    }
    Write-Host ''
    Write-Host 'Dry run successful. Nothing was sent to Discord.' -ForegroundColor Green
    exit 0
}

if (-not (Test-Path -LiteralPath $configurationPath)) {
    throw 'Discord release configuration is missing. Run gradlew.bat configureDiscordRelease first.'
}
if (-not (Test-Path -LiteralPath $keyPath)) {
    throw 'Discord release encryption key is missing. Run gradlew.bat configureDiscordRelease again.'
}

$configuration = Get-Content -LiteralPath $configurationPath -Raw | ConvertFrom-Json
if ([int]$configuration.schemaVersion -ne 2) {
    throw 'Unsupported Discord release configuration. Run configureDiscordRelease again.'
}
$key = [IO.File]::ReadAllBytes($keyPath)
$releasesWebhook = Unprotect-SecretValue $configuration.releasesWebhook $key
$changelogWebhook = Unprotect-SecretValue $configuration.changelogWebhook $key
[Array]::Clear($key, 0, $key.Length)
Test-WebhookUrl $releasesWebhook
Test-WebhookUrl $changelogWebhook

$roleId = [string]$configuration.releasePingRoleId
$username = if ($configuration.webhookUsername) { [string]$configuration.webhookUsername } else { 'Craftbound Releases' }
$allowedMentions = [ordered]@{ parse = @() }
if ($roleId) {
    $releaseMessage = "<@&$roleId>`n$releaseMessage"
    $allowedMentions.roles = @($roleId)
}

$statePath = Join-Path $projectPath ".release-state\discord-$version.json"
$state = [ordered]@{
    version = $version
    jarSha256 = $jarHash
    changelogSha256 = $changelogHash
    releasePosted = $false
    changelogPartsPosted = 0
    changelogPartCount = $changelogParts.Count
}

if ((Test-Path -LiteralPath $statePath) -and -not $Force) {
    $saved = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($saved.jarSha256 -ne $jarHash) {
        throw 'This version was already partly or fully published with a different JAR. Bump the version instead of silently replacing a release.'
    }
    if ($saved.changelogSha256 -ne $changelogHash) {
        throw 'This version was already partly or fully published with a different changelog. Bump the version or review the state before retrying.'
    }
    $state.releasePosted = [bool]$saved.releasePosted
    $state.changelogPartsPosted = [int]$saved.changelogPartsPosted
}

if ($Force) {
    Write-Host 'Force mode: the release and complete changelog will be posted again.' -ForegroundColor Yellow
}
elseif ($state.releasePosted -and $state.changelogPartsPosted -ge $changelogParts.Count) {
    Write-Host 'This exact release was already published. Nothing was sent.' -ForegroundColor Green
    exit 0
}

$client = [Net.Http.HttpClient]::new()
$client.DefaultRequestHeaders.UserAgent.ParseAdd('Craftbound-Gradle-Release/1.0')
try {
    if (-not $state.releasePosted -or $Force) {
        $releasePayload = [ordered]@{
            username = $username
            content = $releaseMessage
            allowed_mentions = $allowedMentions
            attachments = @(
                [ordered]@{
                    id = 0
                    filename = [IO.Path]::GetFileName($jarPath)
                    description = "$modName $version for Minecraft $($properties.minecraft_version)"
                }
            )
        }
        $null = Invoke-DiscordFile $client $releasesWebhook $releasePayload $jarPath
        $state.releasePosted = $true
        Save-PublishState $statePath $state
        Write-Host 'Release message and JAR posted.' -ForegroundColor Green
    }

    $startPart = if ($Force) { 0 } else { [int]$state.changelogPartsPosted }
    for ($index = $startPart; $index -lt $changelogParts.Count; $index++) {
        $partPrefix = if ($changelogParts.Count -gt 1) { "**Part $($index + 1)/$($changelogParts.Count)**`n" } else { '' }
        $changelogPayload = [ordered]@{
            username = $username
            content = $partPrefix + $changelogParts[$index]
            allowed_mentions = [ordered]@{ parse = @() }
        }
        $null = Invoke-DiscordJson $client $changelogWebhook $changelogPayload
        $state.changelogPartsPosted = $index + 1
        Save-PublishState $statePath $state
        Start-Sleep -Milliseconds 750
    }
}
finally {
    $client.Dispose()
    $releasesWebhook = $null
    $changelogWebhook = $null
}

Write-Host ''
Write-Host 'Discord release publication completed successfully.' -ForegroundColor Green
