param(
    [string]$BaseUri = 'http://localhost:18081',
    [ValidateRange(1, 100)]
    [int]$Iterations = 10,
    [switch]$RequireDistinctInstances
)

$ErrorActionPreference = 'Stop'

if (-not ('ConfirmRaceRunner' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

public sealed class ConfirmRaceResult
{
    public int StatusCode { get; private set; }
    public string InstanceId { get; private set; }

    public ConfirmRaceResult(int statusCode, string instanceId)
    {
        StatusCode = statusCode;
        InstanceId = instanceId;
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
                var target = new Uri(index == 0 ? firstUri : secondUri);
                if (target.Scheme != "http")
                {
                    throw new ArgumentException("The lab race verifier requires a local HTTP endpoint.");
                }

                using (var client = new TcpClient())
                using (var connectTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(8)))
                {
                    client.ConnectAsync(target.DnsSafeHost, target.Port, connectTimeout.Token).AsTask().GetAwaiter().GetResult();
                    client.ReceiveTimeout = 8000;
                    client.SendTimeout = 8000;
                    client.NoDelay = true;
                    ready.Signal();
                    if (!release.Wait(TimeSpan.FromSeconds(15)))
                    {
                        throw new TimeoutException("The race barrier was not released in time.");
                    }

                    using (var stream = client.GetStream())
                    using (var reader = new StreamReader(stream, Encoding.ASCII))
                    {
                        // One request on this socket, with no redirect or connection retry.
                        var request = Encoding.ASCII.GetBytes(
                            $"POST {target.PathAndQuery} HTTP/1.1\r\nHost: {target.Authority}\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n");
                        stream.Write(request, 0, request.Length);
                        var parts = (reader.ReadLine() ?? "").Split(' ', 3);
                        if (parts.Length < 2 || !parts[0].StartsWith("HTTP/1.") ||
                            !int.TryParse(parts[1], out int status))
                        {
                            throw new IOException("Expected an HTTP status line.");
                        }

                        string instance = "";
                        string line;
                        const string header = "X-Instance-ID:";
                        while (!string.IsNullOrEmpty(line = reader.ReadLine()))
                        {
                            if (line.StartsWith(header, StringComparison.OrdinalIgnoreCase))
                            {
                                instance = line.Substring(header.Length).Trim();
                            }
                        }
                        return new ConfirmRaceResult(status, instance);
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
    if ($RequireDistinctInstances) {
        $observed = ($results | ForEach-Object { "$($_.InstanceId):$($_.StatusCode)" }) -join ','
        "attempt=$iteration orderId=$($order.id) responses=$observed"
    }
    if ($statuses.Count -ne 2 -or ($statuses -join ',') -ne '200,409') {
        throw "Expected one 200 and one 409 for $($order.id); received $($statuses -join ',')."
    }

    $confirmed = Invoke-RestMethod -Uri "$BaseUri/orders/$($order.id)"
    if ($confirmed.status -ne 'CONFIRMED' -or $null -eq $confirmed.confirmedAt) {
        throw "The final order state is not confirmed for $($order.id)."
    }

    $firstConfirmedAt = [DateTimeOffset]$confirmed.confirmedAt
    $repeat = Invoke-WebRequest -SkipHttpErrorCheck -Uri "$BaseUri/orders/$($order.id)/confirm" -Method Post
    if ($repeat.StatusCode -ne 409) {
        throw "A repeated confirmation must return 409 for $($order.id), got $($repeat.StatusCode)."
    }
    $afterRepeat = Invoke-RestMethod -Uri "$BaseUri/orders/$($order.id)"
    if ([DateTimeOffset]$afterRepeat.confirmedAt -ne $firstConfirmedAt) {
        throw "A repeated confirmation changed confirmedAt for $($order.id)."
    }

    "iteration=$iteration orderId=$($order.id) race=$($statuses -join ',') final=$($confirmed.status) repeat=$($repeat.StatusCode) confirmedAt=$($firstConfirmedAt.ToString('o'))"

    if ($RequireDistinctInstances) {
        if (@($results | Where-Object { $_.InstanceId -notin 'orders-a', 'orders-b' }).Count -gt 0) {
            throw 'Expected both confirmation responses to carry an ID from the replicas profile.'
        }
        if ($results[0].InstanceId -ne $results[1].InstanceId) {
            "crossReplica=PASS orderId=$($order.id) responses=$observed"
            return
        }
        "crossReplica=INCONCLUSIVE attempt=$iteration reason=same-instance"
    }
}

if ($RequireDistinctInstances) {
    throw "No cross-replica race observed within $Iterations attempts."
}
