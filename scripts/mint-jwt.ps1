<#
.SYNOPSIS
  Mints an HS256 JWT for LOCAL testing, signed with JWT_SECRET from .env.

.EXAMPLE
  $alice = .\scripts\mint-jwt.ps1 -Subject alice -Roles CUSTOMER
  curl.exe -H "Authorization: Bearer $alice" http://localhost:8081/api/accounts/<id>

.NOTES
  Development only. Real deployments get tokens from an identity provider (e.g. Amazon Cognito).
  The token is written to stdout only; it is never logged by the services.
#>
param(
    [Parameter(Mandatory)][string]$Subject,
    [ValidateSet("CUSTOMER", "SUPPORT", "ADMIN", "SERVICE")][string[]]$Roles = @("CUSTOMER"),
    [int]$TtlMinutes = 60,
    [string]$EnvFile
)

# Resolved here, not in param(): Windows PowerShell 5.1 leaves $PSScriptRoot empty in parameter
# defaults when the script is started with `powershell -File`.
if (-not $EnvFile) {
    $EnvFile = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..\.env"
}

$secretLine = Get-Content $EnvFile | Where-Object { $_ -match '^JWT_SECRET=' } | Select-Object -First 1
if (-not $secretLine) { throw "JWT_SECRET not found in $EnvFile" }
$secret = $secretLine.Substring("JWT_SECRET=".Length)

function ConvertTo-Base64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header = '{"alg":"HS256","typ":"JWT"}'
$payload = [ordered]@{
    sub   = $Subject
    roles = @($Roles)
    iss   = "securebank-local-dev"
    iat   = $now
    exp   = $now + ($TtlMinutes * 60)
} | ConvertTo-Json -Compress

$utf8 = [System.Text.Encoding]::UTF8
$unsigned = (ConvertTo-Base64Url $utf8.GetBytes($header)) + "." + (ConvertTo-Base64Url $utf8.GetBytes($payload))
$hmac = New-Object System.Security.Cryptography.HMACSHA256 (, $utf8.GetBytes($secret))
$signature = ConvertTo-Base64Url $hmac.ComputeHash($utf8.GetBytes($unsigned))
"$unsigned.$signature"
