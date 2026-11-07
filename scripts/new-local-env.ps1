<#
.SYNOPSIS
  Creates .env (git-ignored) with random local-only secrets for docker compose.
  Values are never printed. Refuses to overwrite an existing .env unless -Force is given.
#>
param([switch]$Force)

$envFile = Join-Path $PSScriptRoot "..\.env"
if ((Test-Path $envFile) -and -not $Force) {
    Write-Host ".env already exists; use -Force to replace it (this would orphan an existing pgdata volume's passwords)."
    exit 0
}

function New-Secret([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    # URL-safe, no characters that need escaping in SQL, YAML or shells
    return ([Convert]::ToBase64String($buffer)).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
}

$lines = @(
    "POSTGRES_PASSWORD=$(New-Secret 24)",
    "ACCOUNT_DB_PASSWORD=$(New-Secret 24)",
    "TRANSACTION_DB_PASSWORD=$(New-Secret 24)",
    "TRANSFER_DB_PASSWORD=$(New-Secret 24)",
    "JWT_SECRET=$(New-Secret 48)"
)
[System.IO.File]::WriteAllLines($envFile, $lines)
Write-Host "Created $((Resolve-Path $envFile).Path) with random local secrets."
