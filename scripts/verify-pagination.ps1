param(
    [string]$BaseUri = 'http://localhost:18081'
)

$ErrorActionPreference = 'Stop'
$customerId = 'customer-003'
$limit = 2
$expectedIds = @(
    '30000000-0000-4000-8000-000000000002',
    '30000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000004',
    '30000000-0000-4000-8000-000000000003'
)

$observedIds = [System.Collections.Generic.List[string]]::new()
$cursor = $null
$firstCursor = $null
$pageNumber = 0
do {
    $query = "limit=$limit"
    if ($null -ne $cursor) {
        $query += '&cursor=' + [uri]::EscapeDataString([string]$cursor)
    }
    $page = Invoke-RestMethod -Uri "$BaseUri/customers/$customerId/orders?$query"
    $pageNumber++
    $ids = @($page.items | ForEach-Object { $_.id })
    if ($ids.Count -gt $limit) {
        throw "Page $pageNumber returned more than $limit items."
    }
    $observedIds.AddRange([string[]]$ids)
    "page=$pageNumber ids=$($ids -join ',') hasNext=$($null -ne $page.nextCursor)"

    if ($pageNumber -eq 1) {
        $firstCursor = $page.nextCursor
    }
    $cursor = $page.nextCursor
    if ($pageNumber -gt 10) {
        throw 'Pagination did not terminate within 10 pages.'
    }
} while ($null -ne $cursor)

if (($observedIds -join ',') -ne ($expectedIds -join ',')) {
    throw "Unexpected page order. Expected $($expectedIds -join ','); received $($observedIds -join ',')."
}
if (($observedIds | Select-Object -Unique).Count -ne $observedIds.Count) {
    throw 'Pagination returned duplicate order IDs.'
}

$invalidCursor = Invoke-WebRequest -SkipHttpErrorCheck -Uri "$BaseUri/customers/$customerId/orders?limit=$limit&cursor=not-a-cursor"
if ($invalidCursor.StatusCode -ne 400) {
    throw "Invalid cursor must return 400, got $($invalidCursor.StatusCode)."
}

$crossCustomerCursor = [uri]::EscapeDataString([string]$firstCursor)
$wrongCustomer = Invoke-WebRequest -SkipHttpErrorCheck -Uri "$BaseUri/customers/customer-001/orders?limit=$limit&cursor=$crossCustomerCursor"
if ($wrongCustomer.StatusCode -ne 400) {
    throw "A cursor used for another customer must return 400, got $($wrongCustomer.StatusCode)."
}

$changedLimit = Invoke-WebRequest -SkipHttpErrorCheck -Uri "$BaseUri/customers/$customerId/orders?limit=1&cursor=$crossCustomerCursor"
if ($changedLimit.StatusCode -ne 400) {
    throw "A cursor used with another limit must return 400, got $($changedLimit.StatusCode)."
}

foreach ($invalidLimit in @(0, 51)) {
    $response = Invoke-WebRequest -SkipHttpErrorCheck -Uri "$BaseUri/customers/$customerId/orders?limit=$invalidLimit"
    if ($response.StatusCode -ne 400) {
        throw "Invalid limit $invalidLimit must return 400, got $($response.StatusCode)."
    }
}

"pagination=PASS pages=$pageNumber ids=$($observedIds -join ',') invalidCursor=400 wrongCustomer=400 changedLimit=400 invalidLimits=400"
