<#
.SYNOPSIS
  End-to-end transfer scenarios against the running docker compose stack (real services, real
  PostgreSQL and Kafka). Prints every request's HTTP status and response body.

.NOTES
  Prerequisite: docker compose up -d (all services healthy). Uses fictional customers only.
  Scenario 9 deliberately stops transaction-service to demonstrate outage recovery.
#>
param([switch]$SkipOutage)

$ErrorActionPreference = "Stop"
$mint = Join-Path $PSScriptRoot "mint-jwt.ps1"
$alice = & $mint -Subject alice -Roles CUSTOMER
$bob = & $mint -Subject bob -Roles CUSTOMER
$agent = & $mint -Subject agent-7 -Roles SUPPORT
$admin = & $mint -Subject ops-admin -Roles ADMIN

function Invoke-Api {
    param([string]$Method, [string]$Url, [string]$Token, [string]$Body, [hashtable]$Headers = @{})
    $curlArgs = @("-s", "-X", $Method, $Url, "-w", "`n%{http_code}")
    if ($Token) { $curlArgs += @("-H", "Authorization: Bearer $Token") }
    foreach ($k in $Headers.Keys) { $curlArgs += @("-H", "${k}: $($Headers[$k])") }
    $tmp = $null
    if ($Body) {
        $tmp = New-TemporaryFile
        [System.IO.File]::WriteAllText($tmp.FullName, $Body)
        $curlArgs += @("-H", "Content-Type: application/json", "--data-binary", "@$($tmp.FullName)")
    }
    try {
        $out = @(& curl.exe @curlArgs)
    } finally {
        if ($tmp) { Remove-Item $tmp.FullName }
    }
    $status = [int]$out[-1]
    $text = if ($out.Length -gt 1) { ($out[0..($out.Length - 2)] -join "`n") } else { "" }
    [pscustomobject]@{ Status = $status; Body = $text; Json = $(if ($text) { $text | ConvertFrom-Json } else { $null }) }
}

function Show([string]$title, $response) {
    Write-Host ""
    Write-Host "### $title"
    Write-Host "HTTP $($response.Status)"
    if ($response.Body) { Write-Host $response.Body }
}

function New-Key { "demo-" + [guid]::NewGuid().ToString("N").Substring(0, 16) }

# docker writes progress to stderr; Windows PowerShell 5.1 would treat that as a terminating error.
function Invoke-Docker {
    $ErrorActionPreference = "Continue"
    & docker @args 2>&1 | ForEach-Object { "$_" }
}

# 1. Accounts -------------------------------------------------------------------------------
$r = Invoke-Api POST "http://localhost:8081/api/accounts" $alice '{"customerName":"Alice Tan"}'
Show "1a. Alice opens an account" $r
$A = $r.Json.id
$r = Invoke-Api POST "http://localhost:8081/api/accounts" $bob '{"customerName":"Bob Lim"}'
Show "1b. Bob opens an account" $r
$B = $r.Json.id

$r = Invoke-Api POST "http://localhost:8081/api/accounts/$A/deposit" $alice '{"amount":1000.00,"reference":"PAYROLL-NOV"}'
Show "1c. Alice deposits 1000.00" $r

# 2. Successful transfer ----------------------------------------------------------------------
$k1 = New-Key
$body1 = "{`"sourceAccountId`":`"$A`",`"beneficiaryAccountId`":`"$B`",`"amount`":250.75,`"description`":`"Rent November`"}"
$r = Invoke-Api POST "http://localhost:8083/api/transfers" $alice $body1 @{ "Idempotency-Key" = $k1 }
Show "2. Transfer 250.75 Alice -> Bob (new Idempotency-Key)" $r
$ref1 = $r.Json.reference

# 3. Idempotency ------------------------------------------------------------------------------
$r = Invoke-Api POST "http://localhost:8083/api/transfers" $alice $body1 @{ "Idempotency-Key" = $k1 }
Show "3a. Client retries the SAME request with the SAME key (replay)" $r
$r = Invoke-Api POST "http://localhost:8083/api/transfers" $alice ($body1 -replace '250.75', '300.00') @{ "Idempotency-Key" = $k1 }
Show "3b. SAME key, DIFFERENT amount" $r
$r = Invoke-Api POST "http://localhost:8083/api/transfers" $alice $body1
Show "3c. Missing Idempotency-Key header" $r

# 4. Business failures ------------------------------------------------------------------------
$r = Invoke-Api POST "http://localhost:8083/api/transfers" $alice ($body1 -replace '250.75', '5000.00') @{ "Idempotency-Key" = (New-Key) }
Show "4a. Insufficient funds (transfer is created, then FAILED)" $r
$r = Invoke-Api POST "http://localhost:8083/api/transfers" $bob $body1 @{ "Idempotency-Key" = (New-Key) }
Show "4b. Bob tries to move money out of Alice's account" $r
$r = Invoke-Api POST "http://localhost:8083/api/transfers" $alice "{`"sourceAccountId`":`"$A`",`"beneficiaryAccountId`":`"$([guid]::NewGuid())`",`"amount`":10}" @{ "Idempotency-Key" = (New-Key) }
Show "4c. Unknown beneficiary" $r
$r = Invoke-Api POST "http://localhost:8083/api/transfers" $alice "{`"sourceAccountId`":`"$A`",`"beneficiaryAccountId`":`"$B`",`"amount`":-5}" @{ "Idempotency-Key" = (New-Key) }
Show "4d. Invalid amount" $r

# 5. Queries ----------------------------------------------------------------------------------
$r = Invoke-Api GET "http://localhost:8083/api/transfers/$ref1" $alice
Show "5a. Alice reads her transfer" $r
$r = Invoke-Api GET "http://localhost:8083/api/transfers/$ref1" $bob
Show "5b. Bob cannot read Alice's transfer" $r
$r = Invoke-Api GET "http://localhost:8083/api/transfers/$ref1/audit" $agent
Show "5c. Support agent reads the saga audit trail" $r

# 6. Balances and ledger ----------------------------------------------------------------------
$r = Invoke-Api GET "http://localhost:8081/api/accounts/$A/balance" $alice
Show "6a. Alice's balance" $r
$r = Invoke-Api GET "http://localhost:8081/api/accounts/$B/balance" $bob
Show "6b. Bob's balance" $r
$r = Invoke-Api GET "http://localhost:8082/api/transactions/account/$B" $bob
Show "6c. Bob's ledger history (double-entry CREDIT line)" $r
$r = Invoke-Api GET "http://localhost:8082/api/transactions/reconciliation/trial-balance" $admin
Show "6d. Ledger trial balance (admin)" $r

# 7. Outbox -----------------------------------------------------------------------------------
Start-Sleep -Seconds 2
Write-Host ""
Write-Host "### 7. Outbox rows for transfer $ref1 (published to Kafka topic securebank.transfer.events)"
Invoke-Docker compose exec -T postgres psql -U postgres -d transfer_db -c "select o.event_type, o.published_at is not null as published, o.attempts from outbox_events o join transfers t on t.id = o.aggregate_id where t.reference = '$ref1' order by o.id;"

# 8/9. Outage of transaction-service ------------------------------------------------------------
if (-not $SkipOutage) {
    Write-Host ""
    Write-Host "### 8. OUTAGE: stopping transaction-service (the ledger)"
    Invoke-Docker compose stop transaction-service | Out-Null

    $k3 = New-Key
    $body3 = "{`"sourceAccountId`":`"$A`",`"beneficiaryAccountId`":`"$B`",`"amount`":100.00,`"description`":`"Groceries split`"}"
    $r = Invoke-Api POST "http://localhost:8083/api/transfers" $alice $body3 @{ "Idempotency-Key" = $k3 }
    Show "8a. Transfer while the ledger is down" $r
    $ref3 = $r.Json.reference
    $r = Invoke-Api GET "http://localhost:8081/api/accounts/$A/balance" $alice
    Show "8b. Alice's balance during the outage (100.00 reserved, not spendable)" $r

    Write-Host ""
    Write-Host "### 9. Restarting transaction-service; the recovery job resumes the saga"
    Invoke-Docker compose start transaction-service | Out-Null
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 5
        $r = Invoke-Api GET "http://localhost:8083/api/transfers/$ref3" $alice
    } while ($r.Json.status -notin @("COMPLETED", "FAILED") -and (Get-Date) -lt $deadline)
    Show "9a. Same transfer after recovery" $r
    $r = Invoke-Api GET "http://localhost:8083/api/transfers/$ref3/audit" $agent
    Show "9b. Audit trail showing the retries" $r
    $r = Invoke-Api GET "http://localhost:8081/api/accounts/$A/balance" $alice
    Show "9c. Alice's final balance" $r
    $r = Invoke-Api GET "http://localhost:8082/api/transactions/reference/$ref3" $agent
    Show "9d. Exactly one ledger entry for the recovered transfer" $r
}
