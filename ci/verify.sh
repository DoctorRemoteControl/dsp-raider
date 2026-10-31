#!/usr/bin/env bash
set -euo pipefail

raider_source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
protocol_source_dir="${DSP408_PROTOCOL_DIR:-${raider_source_dir}/../dsp408-protocol}"

if [[ ! -f "${protocol_source_dir}/pom.xml" ]]; then
    printf '%s\n' 'Missing dsp408-protocol source checkout.' 'Place it alongside Raider or set DSP408_PROTOCOL_DIR to its directory.' >&2
    exit 2
fi

mvn -B -ntp -f "${protocol_source_dir}/pom.xml" install
mvn -B -ntp -f "${raider_source_dir}/pom.xml" verify
