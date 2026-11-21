param(
    [string]$BaseUri = 'http://localhost:18081',
    [ValidateRange(1, 100)]
    [int]$Iterations = 10
)

$ErrorActionPreference = 'Stop'

if (-not ('ConfirmRaceRunner' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

public sealed class ConfirmRaceResult
{
    public int StatusCode { get; private set; }
    public string Body { get; private set; }

    public ConfirmRaceResult(int statusCode, string body)
    {
        StatusCode = statusCode;
        Body = body;
    }
}

public static class ConfirmRaceRunner
{
    public static ConfirmRaceResult[] Run(string uri)
    {
        return Run(uri, uri);
    }

    public static ConfirmRaceResult[] Run(string firstUri, string secondUri)
    {
        using (var ready = new CountdownEvent(2))
        using (var release = new ManualResetEventSlim(false))
        {
            var tasks = Enumerable.Range(0, 2).Select(index => Task.Run(() =>
            {
                using (var handler = new SocketsHttpHandler { UseProxy = false })
                using (var client = new HttpClient(handler))
                {
                    client.Timeout = TimeSpan.FromSeconds(8);
                    ready.Signal();
                    if (!release.Wait(TimeSpan.FromSeconds(15)))
                    {
                        throw new TimeoutException("The race barrier was not released in time.");
                    }

                    string uri = index == 0 ? firstUri : secondUri;
                    using (var content = new StringContent("", Encoding.UTF8, "application/json"))
                    using (var response = client.PostAsync(uri, content).GetAwaiter().GetResult())
                    {
                        string body = response.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                        return new ConfirmRaceResult((int)response.StatusCode, body);
                    }
                }
            })).ToArray();

            try
            {
                if (!ready.Wait(TimeSpan.FromSeconds(15)))
                {
                    throw new TimeoutException("Both confirmation clients did not reach the barrier.");
                }
                release.Set();
                Task.WaitAll(tasks);
                return tasks.Select(task => task.Result).ToArray();
            }
            finally
            {
                release.Set();
            }
        }
    }
}
'@
}

$payload = @{
    customerId = 'customer-001'
    items = @(
        @{ productName = 'Race probe'; quantity = 1; unitPriceCents = 1234 },
        @{ productName = 'Race probe add-on'; quantity = 1; unitPriceCents = 321 }
    )
} | ConvertTo-Json -Depth 5

$firstDifferentOrder = Invoke-RestMethod -Uri "$BaseUri/orders" -Method Post -ContentType 'application/json' -Body $payload
$secondDifferentOrder = Invoke-RestMethod -Uri "$BaseUri/orders" -Method Post -ContentType 'application/json' -Body $payload
$differentOrderResults = @([ConfirmRaceRunner]::Run(
    "$BaseUri/orders/$($firstDifferentOrder.id)/confirm",
    "$BaseUri/orders/$($secondDifferentOrder.id)/confirm"))
$differentOrderStatuses = @($differentOrderResults | ForEach-Object StatusCode | Sort-Object)
if ($differentOrderStatuses.Count -ne 2 -or ($differentOrderStatuses -join ',') -ne '200,200') {
    throw "Distinct pending orders should both confirm; received $($differentOrderStatuses -join ',')."
}
foreach ($distinctOrderId in @($firstDifferentOrder.id, $secondDifferentOrder.id)) {
    $distinctOrder = Invoke-RestMethod -Uri "$BaseUri/orders/$distinctOrderId"
    if ($distinctOrder.status -ne 'CONFIRMED' -or $null -eq $distinctOrder.confirmedAt) {
        throw "A distinct-order confirmation did not persist for $distinctOrderId."
    }
}
"distinctOrders=$($firstDifferentOrder.id),$($secondDifferentOrder.id) race=$($differentOrderStatuses -join ',') final=CONFIRMED,CONFIRMED"

for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
    $order = Invoke-RestMethod -Uri "$BaseUri/orders" -Method Post -ContentType 'application/json' -Body $payload
    $results = @([ConfirmRaceRunner]::Run("$BaseUri/orders/$($order.id)/confirm"))
    $statuses = @($results | ForEach-Object StatusCode | Sort-Object)
    if ($statuses.Count -ne 2 -or ($statuses -join ',') -ne '200,409') {
        throw "Expected one 200 and one 409 for $($order.id); received $($statuses -join ',')."
    }

    $confirmed = Invoke-RestMethod -Uri "$BaseUri/orders/$($order.id)"
    if ($confirmed.status -ne 'CONFIRMED' -or $null -eq $confirmed.confirmedAt) {
        throw "The final order state is not confirmed for $($order.id)."
    }

    $firstConfirmedAt = [string]$confirmed.confirmedAt
    $repeat = Invoke-WebRequest -SkipHttpErrorCheck -Uri "$BaseUri/orders/$($order.id)/confirm" -Method Post
    if ($repeat.StatusCode -ne 409) {
        throw "A repeated confirmation must return 409 for $($order.id), got $($repeat.StatusCode)."
    }
    $afterRepeat = Invoke-RestMethod -Uri "$BaseUri/orders/$($order.id)"
    if ([string]$afterRepeat.confirmedAt -ne $firstConfirmedAt) {
        throw "A repeated confirmation changed confirmedAt for $($order.id)."
    }

    "iteration=$iteration orderId=$($order.id) race=$($statuses -join ',') final=$($confirmed.status) repeat=$($repeat.StatusCode) confirmedAt=$firstConfirmedAt"
}
