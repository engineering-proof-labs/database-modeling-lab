param(
    [Parameter(Mandatory)]
    [ValidateSet('baseline', 'compatible', 'retired')]
    [string]$Phase
)

$ErrorActionPreference = 'Stop'
$v1 = 'http://127.0.0.1:18083/ebr/orders'
$v2 = 'http://127.0.0.1:18084/ebr/orders'

function Assert-Total([string]$BaseUri, [long]$Id, [string]$Field, [decimal]$Expected) {
    $order = Invoke-RestMethod "$BaseUri/$Id" -TimeoutSec 5
    if ($order.id -ne $Id -or $null -eq $order.$Field -or [decimal]$order.$Field -ne $Expected) {
        throw "Unexpected result for $BaseUri/$Id $Field. Expected $Expected."
    }
    $other = if ($Field -eq 'totalCents') { 'totalAmount' } else { 'totalCents' }
    if ($order.PSObject.Properties.Name -contains $other) { throw "Unexpected field $other." }
}

function Set-Total([string]$BaseUri, [long]$Id, [string]$Field, [decimal]$Value) {
    $request = @{}
    $request[$Field] = $Value
    Invoke-RestMethod "$BaseUri/$Id" -Method Put -TimeoutSec 5 -ContentType application/json -Body ($request | ConvertTo-Json -Compress) | Out-Null
}

function Wait-Api([string]$BaseUri) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
    do {
        try {
            $response = Invoke-WebRequest "$BaseUri/1" -TimeoutSec 2 -SkipHttpErrorCheck
            if ($response.StatusCode -eq 200) { return }
        } catch {
            # The API can take a few seconds to start.
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "API did not become ready at $BaseUri."
}

switch ($Phase) {
    'baseline' {
        Wait-Api $v1
        Assert-Total $v1 1 totalCents 1299
        Set-Total $v1 2 totalCents 765
        Assert-Total $v1 2 totalCents 765
        'baseline=PASS seed=1299Cents v1Write=765Cents'
    }
    'compatible' {
        Wait-Api $v2
        Assert-Total $v2 1 totalAmount 12.99
        Assert-Total $v2 2 totalAmount 7.65

        Set-Total $v1 3 totalCents 4201
        Assert-Total $v2 3 totalAmount 42.01
        Set-Total $v1 3 totalCents 4399
        Assert-Total $v2 3 totalAmount 43.99

        Set-Total $v2 4 totalAmount 25.01
        Assert-Total $v1 4 totalCents 2501
        Set-Total $v2 4 totalAmount 26.07
        Assert-Total $v1 4 totalCents 2607

        $bad = Invoke-WebRequest "$v2/4" -Method Put -TimeoutSec 5 -SkipHttpErrorCheck -ContentType application/json -Body '{"totalAmount":26.071}'
        if ($bad.StatusCode -ne 400) { throw 'Expected a 400 for fractional cents.' }
        Assert-Total $v2 4 totalAmount 26.07

        docker compose --profile ebr stop api-ebr-v2
        if ($LASTEXITCODE) { throw 'Could not stop version 2 for the rollback drill.' }
        try {
            Assert-Total $v1 4 totalCents 2607
            Set-Total $v1 4 totalCents 3105
            Assert-Total $v1 4 totalCents 3105
        } finally {
            docker compose --profile ebr up -d --no-deps --no-build api-ebr-v2
            if ($LASTEXITCODE) { throw 'Could not restore version 2.' }
        }
        Wait-Api $v2
        Assert-Total $v2 4 totalAmount 31.05
        'compatible=PASS oldRows=true v1ToV2=true v2ToV1=true rollbackToV1=true invalidScale=400'
    }
    'retired' {
        Assert-Total $v2 1 totalAmount 12.99
        Assert-Total $v2 4 totalAmount 31.05
        Set-Total $v2 5 totalAmount 99.01
        Assert-Total $v2 5 totalAmount 99.01
        $legacy = Invoke-WebRequest "$v1/1" -TimeoutSec 5 -SkipHttpErrorCheck
        if ($legacy.StatusCode -ne 500) { throw "Expected the retired V1 view to fail. Got $($legacy.StatusCode)." }
        'retired=PASS v2Reads=true v2Writes=true v1Read=500'
    }
}
