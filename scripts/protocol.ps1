# Keeps the app's types for hanab.live's wire protocol in step with the website's own.
#
# The website's server owns every message in its `hanabi_protocol` crate. native/protocol depends on that crate at a
# pinned commit and writes Kotlin types for it (app/src/main/java/live/hanab/client/protocol/Protocol.kt). The
# website's shared test cases at the same commit are kept in app/src/test/resources/protocol, and a unit test runs
# them against those types.
#
#   .\scripts\protocol.ps1 gen        write Protocol.kt from the pinned commit
#   .\scripts\protocol.ps1 check      fail if Protocol.kt is not what gen writes (it was edited, or the pin moved without it)
#   .\scripts\protocol.ps1 outdated   fail if the protocol of the website that is live has changed since the pin, and say what changed
#   .\scripts\protocol.ps1 bump [rev] move the pin (default: the commit the website is running now; `main` for the
#                                    website's newest), refresh the test cases, run gen
#
# Needs cargo (rustup fetches the pinned toolchain). `outdated` and `bump` also use the GitHub API (GITHUB_TOKEN if set).
param(
    [Parameter(Mandatory = $true, Position = 0)][ValidateSet('gen', 'check', 'outdated', 'bump')][string]$Command,
    [Parameter(Position = 1)][string]$Rev
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tool = Join-Path $root 'native/protocol'
$manifest = Join-Path $tool 'Cargo.toml'
$generated = Join-Path $root 'app/src/main/java/live/hanab/client/protocol/Protocol.kt'
$fixtures = Join-Path $root 'app/src/test/resources/protocol/compatibility.json'
$repo = 'Hanabi-Live/hanabi-live'

function Get-Pin {
    $line = Get-Content $manifest | Where-Object { $_ -match 'hanabi_protocol' } | Select-Object -First 1
    if ($line -notmatch 'rev = "([0-9a-f]+)"') { throw "No rev for hanabi_protocol in $manifest" }
    $Matches[1]
}

function Invoke-Generator([string]$Out) {
    Push-Location $tool
    try {
        cargo run --quiet -- $Out
        if ($LASTEXITCODE -ne 0) { throw "The generator failed" }
    } finally { Pop-Location }
}

function Invoke-GitHub([string]$Path) {
    $headers = @{ 'User-Agent' = 'hanab-android'; 'Accept' = 'application/vnd.github+json' }
    if ($env:GITHUB_TOKEN) { $headers['Authorization'] = "Bearer $env:GITHUB_TOKEN" }
    Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/$Path" -Headers $headers
}

function Get-UpstreamHead {
    # `git ls-remote` needs no API token.
    $line = git ls-remote "https://github.com/$repo" HEAD
    if (-not $line) { throw "Could not read the website's repository" }
    ($line -split '\s+')[0]
}

# The commit the website is running: its client's script is named for it (that is the version the server checks).
function Get-DeployedRev {
    $html = (Invoke-WebRequest -Uri 'https://hanab.live/' -UseBasicParsing).Content
    if ($html -notmatch '/public/js/bundles/main\.([0-9a-f]{40})\.min\.js') { throw "Could not read the website's version" }
    $Matches[1]
}

switch ($Command) {
    'gen' {
        New-Item -ItemType Directory -Force (Split-Path $generated) | Out-Null
        Invoke-Generator $generated
        Write-Host "Wrote $generated (pinned to $(Get-Pin))"
    }
    'check' {
        $temp = Join-Path ([IO.Path]::GetTempPath()) "Protocol-$([guid]::NewGuid()).kt"
        try {
            Invoke-Generator $temp
            if ((Get-Content $temp -Raw) -ne (Get-Content $generated -Raw)) {
                Write-Error "Protocol.kt is not what the generator writes for the pinned commit. Run scripts/protocol.ps1 gen."
                exit 1
            }
            Write-Host "Protocol.kt matches the pinned commit $(Get-Pin)."
        } finally { Remove-Item $temp -ErrorAction SilentlyContinue }
    }
    'outdated' {
        $pin = Get-Pin
        $head = Get-DeployedRev
        # The protocol lives in server/protocol; the website's generated TypeScript for it is checked in beside the clients, and
        # the frames around the payloads (frames.ts, written by hand) are what net/WireFrames.kt follows.
        $paths = 'server/protocol', 'packages/data/src/generated', 'packages/game/src/generated', 'packages/data/src/functions/frames.ts'
        $touching = foreach ($path in $paths) {
            Invoke-GitHub "commits?path=$path&sha=$head&per_page=100" | Where-Object { $_.sha -ne $pin }
        }
        $compare = Invoke-GitHub "compare/$pin...$head"
        $changed = @($compare.files | Where-Object {
            $name = $_.filename
            $paths | Where-Object { $name.StartsWith($_) }
        })
        if ($changed.Count -eq 0) {
            Write-Host "The protocol of the website that is live ($head) has not changed since the pinned commit $pin."
            exit 0
        }
        $commits = $compare.commits | Where-Object { $sha = $_.sha; $touching | Where-Object { $_.sha -eq $sha } }
        Write-Host "The website that is live ($head) has a different protocol from the pinned commit ${pin}:"
        $commits | ForEach-Object { Write-Host ("  {0} {1}" -f $_.sha.Substring(0, 8), ($_.commit.message -split "`n")[0]) }
        Write-Host "$($changed.Count) file(s): $(($changed | ForEach-Object filename) -join ', ')"
        Write-Host "Run scripts/protocol.ps1 bump, fix what the build and the tests then show, and commit the result."
        exit 1
    }
    'bump' {
        if (-not $Rev) { $Rev = Get-DeployedRev } elseif ($Rev -eq 'main') { $Rev = Get-UpstreamHead }
        $Rev = (Invoke-GitHub "commits/$Rev").sha
        $text = (Get-Content $manifest -Raw) -replace '(hanabi_protocol\s*=\s*\{[^}]*rev = ")[0-9a-f]+(")', "`${1}$Rev`${2}"
        [IO.File]::WriteAllText($manifest, $text)
        Push-Location $tool
        try {
            cargo update --quiet --package hanabi_protocol
            if ($LASTEXITCODE -ne 0) { throw "cargo update failed" }
        } finally { Pop-Location }
        $raw = "https://raw.githubusercontent.com/$repo/$Rev/server/protocol/fixtures/compatibility.json"
        New-Item -ItemType Directory -Force (Split-Path $fixtures) | Out-Null
        Invoke-WebRequest -Uri $raw -OutFile $fixtures -UseBasicParsing
        Invoke-Generator $generated
        Write-Host "Pinned to $Rev. Build and run the tests (.\build.ps1 testDebugUnitTest): what fails is what changed."
        git -C $root diff --stat
    }
}
