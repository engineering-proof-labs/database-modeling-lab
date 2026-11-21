param(
    [Parameter(Mandatory)]
    [ValidateSet('postgres', 'oracle', 'mongodb', 'dynamodb')]
    [string]$Profile,
    [string]$BaseUri = 'http://127.0.0.1:18081'
)

$ErrorActionPreference = 'Stop'
$reference = '2040-01-15T12:00:00Z'
$fixtures = @(
    @{ number = 1; customer = 'customer-001'; time = '2040-01-15T11:30:00.000Z'; status = 'PENDING' }
    @{ number = 2; customer = 'customer-002'; time = '2040-01-15T11:30:00.001Z'; status = 'PENDING' }
    @{ number = 3; customer = 'customer-001'; time = '2040-01-15T11:59:59.999Z'; status = 'PENDING' }
    @{ number = 4; customer = 'customer-002'; time = '2040-01-15T11:59:59.999Z'; status = 'PENDING' }
    @{ number = 5; customer = 'customer-001'; time = '2040-01-15T11:29:59.999Z'; status = 'PENDING' }
    @{ number = 6; customer = 'customer-001'; time = '2040-01-15T12:00:00.000Z'; status = 'PENDING' }
    @{ number = 7; customer = 'customer-002'; time = '2040-01-15T12:00:00.001Z'; status = 'PENDING' }
    @{ number = 8; customer = 'customer-002'; time = '2040-01-15T11:45:00.000Z'; status = 'CONFIRMED' }
)

function Get-FixtureId([int]$Number) {
    '60000000-0000-4000-8000-{0:D12}' -f $Number
}

function Wait-Api {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
    do {
        try {
            $response = Invoke-WebRequest "$BaseUri/orders/pending?limit=2" -SkipHttpErrorCheck -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch {
            # The API may still be starting.
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    docker compose --profile $Profile logs --tail 25 "api-$Profile"
    throw "$Profile API did not become ready."
}

function Set-Clock([string]$Instant) {
    $env:LAB_CLOCK_INSTANT = $Instant
    docker compose --profile $Profile up -d --no-deps --no-build "api-$Profile"
    if ($LASTEXITCODE) { throw 'API recreation failed.' }
    Wait-Api
}

function Set-Fixtures {
    switch ($Profile) {
        { $_ -in @('postgres', 'oracle') } {
            $ids = ($fixtures | ForEach-Object { "'$(Get-FixtureId $_.number)'" }) -join ','
            $sql = @("DELETE FROM orders WHERE id IN ($ids);")
            foreach ($row in $fixtures) {
                $id = Get-FixtureId $row.number
                $time = if ($Profile -eq 'oracle') {
                    "TIMESTAMP '$($row.time.Replace('T', ' ').TrimEnd('Z'))'"
                } else {
                    "TIMESTAMPTZ '$($row.time)'"
                }
                $confirmed = if ($row.status -eq 'CONFIRMED') { $time } else { 'NULL' }
                $sql += "INSERT INTO orders (id, customer_id, status, total_cents, created_at, confirmed_at) VALUES ('$id', '$($row.customer)', '$($row.status)', 100, $time, $confirmed);"
                $sql += "INSERT INTO order_items (order_id, item_number, product_name, quantity, unit_price_cents, line_total_cents) VALUES ('$id', 1, 'Pending probe', 1, 100, 100);"
            }
            if ($Profile -eq 'postgres') {
                (@('BEGIN;') + $sql + @('COMMIT;')) -join [Environment]::NewLine |
                    docker compose --profile postgres exec -T postgres psql -v ON_ERROR_STOP=1 -U orders_app -d orders
            } else {
                (@('WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK') + $sql + @('COMMIT;', 'EXIT SUCCESS')) -join [Environment]::NewLine |
                    docker compose --profile oracle exec -T oracle sqlplus -L -s orders_app/OrdersLocal42@//localhost:1521/FREEPDB1
            }
            if ($LASTEXITCODE) { throw "Could not seed $Profile." }
        }
        'mongodb' {
            $rows = $fixtures | ForEach-Object {
                @{ id = Get-FixtureId $_.number; customer = $_.customer; time = $_.time; status = $_.status }
            }
            $json = ConvertTo-Json -InputObject @($rows) -Compress
            $source = @"
const fixtures = $json;
for (const row of fixtures) {
  const createdAt = new Date(row.time);
  db.orders.replaceOne({_id: row.id}, {
    _id: row.id, customerId: row.customer, status: row.status, totalCents: NumberLong('100'),
    createdAt, confirmedAt: row.status === 'CONFIRMED' ? createdAt : null,
    items: [{itemNumber: 1, productName: 'Pending probe', quantity: 1,
             unitPriceCents: NumberLong('100'), lineTotalCents: NumberLong('100')}]
  }, {upsert: true});
}
"@
            $source | docker compose --profile mongodb exec -T mongodb mongosh orders --quiet --file /dev/stdin
            if ($LASTEXITCODE) { throw 'Could not seed MongoDB.' }
        }
        'dynamodb' {
            $request = @('curl', '-fsS', '--aws-sigv4', 'aws:amz:us-east-1:dynamodb', '--user', 'local:local',
                '-H', 'Content-Type: application/x-amz-json-1.0', '-H', 'X-Amz-Target: DynamoDB_20120810.PutItem',
                '--data-binary', '@-', 'http://localhost:8000/')
            foreach ($row in $fixtures) {
                $id = Get-FixtureId $row.number
                $epoch = [DateTimeOffset]::Parse($row.time).ToUnixTimeMilliseconds()
                $sort = '{0:x16}#{1}' -f ($epoch -bxor [long]::MinValue), $id
                $item = @{
                    id = @{ S = $id }; customerId = @{ S = $row.customer }
                    status = @{ S = $row.status }; totalCents = @{ N = '100' }
                    createdAt = @{ N = "$epoch" }; createdOrder = @{ S = $sort }
                    items = @{ L = @(@{ M = @{
                        itemNumber = @{ N = '1' }; productName = @{ S = 'Pending probe' }
                        quantity = @{ N = '1' }; unitPriceCents = @{ N = '100' }; lineTotalCents = @{ N = '100' }
                    } }) }
                }
                if ($row.status -eq 'PENDING') {
                    $item.pendingBucket = @{ S = 'PENDING' }
                } else {
                    $item.confirmedAt = @{ N = "$epoch" }
                }
                @{ TableName = 'orders-lab'; Item = $item } | ConvertTo-Json -Depth 10 -Compress |
                    docker compose --profile dynamodb exec -T dynamodb @request | Out-Null
                if ($LASTEXITCODE) { throw "Could not seed DynamoDB item $id." }
            }
        }
    }
}

function Assert-Page($Page, [int[]]$Numbers, [bool]$HasNext) {
    $expected = ($Numbers | ForEach-Object { Get-FixtureId $_ }) -join ','
    $actual = ($Page.items.id) -join ','
    if ($actual -ne $expected -or [bool]$Page.nextCursor -ne $HasNext) {
        throw "Expected IDs $expected and hasNext=$HasNext. Got $actual and hasNext=$([bool]$Page.nextCursor)."
    }
    if (@($Page.items | Where-Object status -ne 'PENDING').Count -ne 0) {
        throw 'A non-pending order appeared.'
    }
}

$previousClock = $env:LAB_CLOCK_INSTANT
try {
    $env:LAB_CLOCK_INSTANT = $reference
    docker compose --profile $Profile up --build -d
    if ($LASTEXITCODE) { throw "Could not start $Profile." }
    Wait-Api
    Set-Fixtures
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    do {
        $snapshot = Invoke-RestMethod "$BaseUri/orders/pending?limit=50"
        if ($Profile -ne 'dynamodb' -or @($snapshot.items).Count -ge 4) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    Assert-Page $snapshot @(4, 3, 2, 1) $false
    $first = Invoke-RestMethod "$BaseUri/orders/pending?limit=2"
    Assert-Page $first @(4, 3) $true

    foreach ($query in @('limit=0', 'limit=51', 'limit=2&cursor=invalid', "limit=1&cursor=$($first.nextCursor)")) {
        $invalid = Invoke-WebRequest "$BaseUri/orders/pending?$query" -SkipHttpErrorCheck
        if ($invalid.StatusCode -ne 400) { throw "Expected 400, got $($invalid.StatusCode) for $query." }
    }

    Set-Clock '2040-01-15T13:00:00Z'
    $second = Invoke-RestMethod "$BaseUri/orders/pending?limit=2&cursor=$($first.nextCursor)"
    Assert-Page $second @(2, 1) $false
    Assert-Page (Invoke-RestMethod "$BaseUri/orders/pending?limit=2") @() $false

    Set-Clock $reference
    $confirmedId = Get-FixtureId 3
    $confirmed = Invoke-RestMethod "$BaseUri/orders/$confirmedId/confirm" -Method Post
    if ($confirmed.status -ne 'CONFIRMED' -or !$confirmed.confirmedAt) { throw 'Confirmation failed.' }
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    do {
        $after = Invoke-RestMethod "$BaseUri/orders/pending?limit=50"
        if ($Profile -ne 'dynamodb' -or $confirmedId -notin $after.items.id) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    Assert-Page $after @(4, 2, 1) $false
    Assert-Page (Invoke-RestMethod "$BaseUri/orders/pending?limit=2&cursor=$($first.nextCursor)") @(2, 1) $false
    "pending=PASS profile=$Profile asOf=$reference pages=4,3/2,1 afterConfirm=4,2,1"
} finally {
    $env:LAB_CLOCK_INSTANT = $previousClock
    docker compose --profile $Profile up -d --no-deps --no-build "api-$Profile"
    if ($LASTEXITCODE) { throw 'Failed to restore the API clock.' }
}
