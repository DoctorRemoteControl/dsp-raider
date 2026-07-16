param(
    [string] $BasePath = "DspLib-408.json",
    [string] $FirSourcePath = "DspLib-408-fir.full.json",
    [string] $OutputPath = "DspLib-408-fir.json"
)

$ErrorActionPreference = "Stop"

function Read-JsonHashtable {
    param([string] $Path)
    Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json -AsHashtable
}

function Get-ValueOrNull {
    param(
        [System.Collections.IDictionary] $Map,
        [string] $Key
    )
    if ($null -ne $Map -and $Map.Contains($Key)) {
        return $Map[$Key]
    }
    return $null
}

function Convert-HexByteToInt {
    param($Value)
    if ($Value -is [int]) {
        return $Value
    }
    $text = [string] $Value
    if ($text.StartsWith("0x", [System.StringComparison]::OrdinalIgnoreCase)) {
        return [Convert]::ToInt32($text.Substring(2), 16)
    }
    return [Convert]::ToInt32($text, 10)
}

function Convert-EvidenceSources {
    param($Sources)

    $out = New-Object System.Collections.Generic.List[string]
    foreach ($source in $Sources) {
        if ($source -is [string]) {
            $out.Add($source)
            continue
        }

        if ($source -is [System.Collections.IDictionary]) {
            foreach ($key in @("path", "output_dir", "summary", "results_csv", "script")) {
                if ($source.Contains($key) -and -not [string]::IsNullOrWhiteSpace([string] $source[$key])) {
                    $out.Add([string] $source[$key])
                    break
                }
            }
        }
    }

    return @($out | Select-Object -Unique)
}

function New-CompatDevice {
    param(
        [System.Collections.IDictionary] $BaseDevice,
        [System.Collections.IDictionary] $FirDevice
    )

    $device = [ordered] @{}
    foreach ($field in $BaseDevice.Keys) {
        if ($field -eq "channel_counts") {
            $counts = Get-ValueOrNull $FirDevice "channel_counts"
            $device[$field] = [ordered] @{
                inputs = [int] $counts["inputs"]
                outputs = [int] $counts["outputs"]
                total = [int] $counts["total"]
            }
        } elseif ($FirDevice.Contains($field)) {
            $device[$field] = $FirDevice[$field]
        } else {
            $device[$field] = $BaseDevice[$field]
        }
    }
    return $device
}

function New-CompatChannels {
    param(
        [System.Collections.IDictionary] $BaseChannels,
        [System.Collections.IDictionary] $FirChannels
    )

    $channels = [ordered] @{}
    foreach ($field in $BaseChannels.Keys) {
        if ($field -eq "index_map") {
            $indexMap = [ordered] @{}
            foreach ($channel in $BaseChannels["index_map"].Keys) {
                $indexMap[$channel] = Convert-HexByteToInt $FirChannels["index_map"][$channel]
            }
            $channels[$field] = $indexMap
        } elseif ($FirChannels.Contains($field)) {
            $channels[$field] = $FirChannels[$field]
        } else {
            $channels[$field] = $BaseChannels[$field]
        }
    }
    return $channels
}

function New-CompatEnums {
    param(
        [System.Collections.IDictionary] $BaseEnums,
        [System.Collections.IDictionary] $FirEnums
    )

    $enums = [ordered] @{}
    foreach ($field in $BaseEnums.Keys) {
        $enums[$field] = if ($FirEnums.Contains($field)) { $FirEnums[$field] } else { $BaseEnums[$field] }
    }
    foreach ($field in @("input_compressor_types", "preset_slots")) {
        if ($FirEnums.Contains($field) -and -not $enums.Contains($field)) {
            $enums[$field] = $FirEnums[$field]
        }
    }
    return $enums
}

function New-CompatValueModels {
    param(
        [System.Collections.IDictionary] $BaseModels,
        [System.Collections.IDictionary] $FirModels
    )

    $models = [ordered] @{}
    foreach ($field in $BaseModels.Keys) {
        $models[$field] = if ($FirModels.Contains($field)) { $FirModels[$field] } else { $BaseModels[$field] }
    }
    foreach ($field in @("fir_tap_count")) {
        if ($FirModels.Contains($field) -and -not $models.Contains($field)) {
            $models[$field] = $FirModels[$field]
        }
    }
    return $models
}

function New-CompatParameters {
    param(
        [System.Collections.IDictionary] $BaseParameters,
        [System.Collections.IDictionary] $FirParameters
    )

    $parameters = [ordered] @{}

    foreach ($field in $BaseParameters.Keys) {
        if ($FirParameters.Contains($field)) {
            $parameters[$field] = $FirParameters[$field]
        } else {
            $parameters[$field] = $BaseParameters[$field]
        }
    }

    foreach ($field in @(
            "input_compressor",
            "preset_names",
            "fir_processing_mode",
            "fir_generator",
            "fir_filter_type",
            "fir_window_function",
            "fir_tap_count",
            "fir_highpass_frequency",
            "fir_lowpass_frequency",
            "fir_ok_apply",
            "external_fir_upload",
            "config_dump",
            "fir_file_names",
            "fir_chunk_read"
        )) {
        if ($FirParameters.Contains($field) -and -not $parameters.Contains($field)) {
            $parameters[$field] = $FirParameters[$field]
        }
    }

    return $parameters
}

$base = Read-JsonHashtable $BasePath
$fir = Read-JsonHashtable $FirSourcePath

$out = [ordered] @{}
foreach ($field in $base.Keys) {
    switch ($field) {
        "schema_version" {
            $out[$field] = "4.0"
        }
        "library_type" {
            $out[$field] = "dsp408_fir_field_library"
        }
        "evidence_policy" {
            $out[$field] = "facts are retained from the FIR408 evidence library; heavy capture/probe details are stored in DspLib-408-fir.full.json"
        }
        "evidence_sources" {
            $out[$field] = Convert-EvidenceSources $fir[$field]
        }
        "device" {
            $out[$field] = New-CompatDevice $base[$field] $fir[$field]
        }
        "channels" {
            $out[$field] = New-CompatChannels $base[$field] $fir[$field]
        }
        "enums" {
            $out[$field] = New-CompatEnums $base[$field] $fir[$field]
        }
        "value_models" {
            $out[$field] = New-CompatValueModels $base[$field] $fir[$field]
        }
        "parameters" {
            $out[$field] = New-CompatParameters $base[$field] $fir[$field]
        }
        default {
            $out[$field] = if ($fir.Contains($field)) { $fir[$field] } else { $base[$field] }
        }
    }
}

$json = $out | ConvertTo-Json -Depth 100
Set-Content -LiteralPath $OutputPath -Value $json -Encoding utf8
