param(
    [string]$ApiBase = "http://192.168.1.146:8181/api/v1",
    [Parameter(Mandatory = $true)]
    [string]$Label,
    [int]$DurationSeconds = 20,
    [int]$PollIntervalMs = 100,
    [Parameter(Mandatory = $true)]
    [string]$OutPath
)

$ErrorActionPreference = "Stop"

if ($DurationSeconds -lt 1) {
    throw "DurationSeconds must be at least 1."
}
if ($PollIntervalMs -lt 50) {
    throw "PollIntervalMs must be at least 50."
}

$resolvedOutPath = [System.IO.Path]::GetFullPath($OutPath)
$outDirectory = Split-Path -Parent $resolvedOutPath
if (-not [string]::IsNullOrWhiteSpace($outDirectory)) {
    New-Item -ItemType Directory -Path $outDirectory -Force | Out-Null
}

$startedAt = [DateTimeOffset]::UtcNow
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($DurationSeconds)
$samples = [System.Collections.Generic.List[object]]::new()
$errors = [System.Collections.Generic.List[object]]::new()
$lastObservedAt = $null

while ([DateTimeOffset]::UtcNow -lt $deadline) {
    try {
        $response = Invoke-RestMethod -Uri "$ApiBase/meters" -Method Get -TimeoutSec 5
        $meters = $response.meters
        if ($null -ne $meters -and
            $null -ne $meters.observedAtEpochMs -and
            $meters.observedAtEpochMs -ne $lastObservedAt) {
            $lastObservedAt = $meters.observedAtEpochMs
            $channelSamples = @(
                foreach ($channel in $meters.channels) {
                    [ordered]@{
                        id = $channel.id
                        rawLowByte = $channel.rawLowByte
                        rawHighByte = $channel.rawHighByte
                        rawValue = $channel.rawValue
                        aux = $channel.aux
                        levelDb = $channel.levelDb
                        limiterActive = $channel.limiterActive
                        clipActive = $channel.clipActive
                    }
                }
            )
            $samples.Add([ordered]@{
                capturedAtEpochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                observedAtEpochMs = $meters.observedAtEpochMs
                rawPayloadHex = $meters.rawPayloadHex
                statusByteRaw = $meters.statusByteRaw
                tailBytesRaw = @($meters.tailBytesRaw)
                channels = $channelSamples
            })
        }
    } catch {
        $errors.Add([ordered]@{
            capturedAtEpochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            message = $_.Exception.Message
        })
    }
    Start-Sleep -Milliseconds $PollIntervalMs
}

$endedAt = [DateTimeOffset]::UtcNow
$document = [ordered]@{
    schema = "fir408-runtime-meter-capture-v1"
    label = $Label
    apiBase = $ApiBase
    startedAtEpochMs = $startedAt.ToUnixTimeMilliseconds()
    endedAtEpochMs = $endedAt.ToUnixTimeMilliseconds()
    durationSeconds = [Math]::Round(($endedAt - $startedAt).TotalSeconds, 3)
    pollIntervalMs = $PollIntervalMs
    uniqueSamples = $samples.Count
    errors = @($errors)
    samples = @($samples)
}

$document | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $resolvedOutPath -Encoding utf8

function Show-ChannelRange([string]$channelId) {
    $values = @(
        foreach ($sample in $samples) {
            $channel = $sample.channels | Where-Object id -EQ $channelId | Select-Object -First 1
            if ($null -ne $channel) {
                [pscustomobject]@{
                    Raw = [int]$channel.rawValue
                    Aux = [int]$channel.aux
                }
            }
        }
    )
    if ($values.Count -eq 0) {
        return "$channelId=no samples"
    }
    $rawMeasure = $values.Raw | Measure-Object -Minimum -Maximum
    $auxDistinct = @($values.Aux | Sort-Object -Unique)
    return "{0}=raw 0x{1:X4}..0x{2:X4}, aux=[{3}]" -f `
        $channelId, [int]$rawMeasure.Minimum, [int]$rawMeasure.Maximum, `
        (($auxDistinct | ForEach-Object { "0x{0:X2}" -f $_ }) -join ",")
}

$tailValues = @(
    foreach ($sample in $samples) {
        if ($null -ne $sample.rawPayloadHex) {
            ($sample.rawPayloadHex -split "\s+")[40..45] -join " "
        }
    }
) | Sort-Object -Unique

Write-Output ("capture={0}" -f $resolvedOutPath)
Write-Output ("uniqueSamples={0}; errors={1}" -f $samples.Count, $errors.Count)
Write-Output (Show-ChannelRange "ina")
Write-Output (Show-ChannelRange "inb")
Write-Output ("distinctTailBytes={0}" -f ($tailValues -join " | "))
