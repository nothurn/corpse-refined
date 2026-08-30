[CmdletBinding()]
param(
    [string]$ProjectDirectory = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function ConvertTo-PlainText {
    param([Security.SecureString]$SecureValue)

    return [Net.NetworkCredential]::new('', $SecureValue).Password
}

function Protect-SecretValue {
    param(
        [string]$PlainValue,
        [byte[]]$Key
    )

    $aes = [Security.Cryptography.Aes]::Create()
    try {
        $aes.KeySize = 256
        $aes.Key = $Key
        $aes.GenerateIV()
        $encryptor = $aes.CreateEncryptor()
        try {
            $plainBytes = [Text.Encoding]::UTF8.GetBytes($PlainValue)
            $cipherBytes = $encryptor.TransformFinalBlock($plainBytes, 0, $plainBytes.Length)
            return [ordered]@{
                iv = [Convert]::ToBase64String($aes.IV)
                ciphertext = [Convert]::ToBase64String($cipherBytes)
            }
        }
        finally {
            $encryptor.Dispose()
        }
    }
    finally {
        $aes.Dispose()
    }
}

function Protect-FileForCurrentUser {
    param([string]$Path)

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $null = & icacls.exe $Path '/inheritance:r' '/grant:r' "${identity}:(M)"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not restrict access to $Path for $identity."
    }
}

function Read-DiscordWebhook {
    param([string]$Prompt)

    while ($true) {
        $secureValue = Read-Host -Prompt $Prompt -AsSecureString
        $plainValue = ConvertTo-PlainText $secureValue
        try {
            if ($plainValue -match '^https://(?:canary\.|ptb\.)?discord(?:app)?\.com/api/webhooks/[0-9]+/[A-Za-z0-9._-]+$') {
                return $secureValue
            }
        }
        finally {
            $plainValue = $null
        }

        Write-Host 'That is not a valid Discord incoming-webhook URL. Please try again.' -ForegroundColor Yellow
    }
}

$projectPath = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$configPath = Join-Path $projectPath '.discord-release-secrets.json'
$keyPath = Join-Path $projectPath '.discord-release-key.bin'

Write-Host ''
Write-Host 'Craftbound Discord release setup' -ForegroundColor Cyan
Write-Host 'The pasted URLs stay hidden and are encrypted locally with AES-256.'
Write-Host 'The key and configuration are restricted to the current Windows user.'
Write-Host ''

$releasesWebhook = Read-DiscordWebhook 'Paste the webhook URL for #releases'
$changelogWebhook = Read-DiscordWebhook 'Paste the webhook URL for #changelog'
$releaseRoleId = Read-Host 'Optional Release Ping role ID (press Enter for no automatic ping)'

if ($releaseRoleId -and $releaseRoleId -notmatch '^[0-9]{15,22}$') {
    throw 'The role ID must contain only 15 to 22 digits.'
}

$key = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($key)
}
finally {
    $random.Dispose()
}

$releasesPlain = ConvertTo-PlainText $releasesWebhook
$changelogPlain = ConvertTo-PlainText $changelogWebhook
try {
    $configuration = [ordered]@{
        schemaVersion = 2
        encryption = 'AES-256-CBC'
        releasesWebhook = Protect-SecretValue $releasesPlain $key
        changelogWebhook = Protect-SecretValue $changelogPlain $key
        releasePingRoleId = $releaseRoleId
        webhookUsername = 'Craftbound Releases'
    }

    [IO.File]::WriteAllBytes($keyPath, $key)
    $configuration | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $configPath -Encoding UTF8
    Protect-FileForCurrentUser $keyPath
    Protect-FileForCurrentUser $configPath
}
finally {
    $releasesPlain = $null
    $changelogPlain = $null
    [Array]::Clear($key, 0, $key.Length)
}

Write-Host ''
Write-Host "Encrypted configuration saved to $configPath" -ForegroundColor Green
Write-Host 'Run gradlew.bat validateDiscordRelease first, then gradlew.bat publishDiscordRelease.'
