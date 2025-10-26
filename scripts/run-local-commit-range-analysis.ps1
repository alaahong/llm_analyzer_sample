<#
Run PrChangeTestAnalyzer locally on Windows PowerShell with a commit range and a local Ollama model.

Usage:
  scripts\run-local-commit-range-analysis.ps1 <BASE_SHA_OR_REF> <HEAD_SHA_OR_REF> [-Model <ollama_model>] [-FailOnTestFailure <bool>]

Example:
  scripts\run-local-commit-range-analysis.ps1 main HEAD -Model "llama3.1:8b-instruct"
#>
param(
    [Parameter(Mandatory = $true)][string]$BaseRef,
    [Parameter(Mandatory = $true)][string]$HeadRef,
    [string]$Model = "llama3.1:8b-instruct",
    [bool]$FailOnTestFailure = $true
)

$ErrorActionPreference = "Stop"

function Resolve-GitSha([string]$ref) {
    (git rev-parse $ref).Trim()
}

$BASE_SHA = Resolve-GitSha $BaseRef
$HEAD_SHA = Resolve-GitSha $HeadRef
Write-Host "[info] Commit range: $BASE_SHA .. $HEAD_SHA"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "java not found. Please install Java 21+ and retry."
}

# Ensure Ollama
$baseUrl = "http://127.0.0.1:11434"
try {
    Invoke-RestMethod -Uri "$baseUrl/v1/models" -TimeoutSec 5 | Out-Null
} catch {
    if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
        throw "ollama not found. Install from https://ollama.com and retry."
    }
    Write-Host "[info] Starting ollama serve ..."
    Start-Process -FilePath "ollama" -ArgumentList "serve" -NoNewWindow
    $ok = $false
    for ($i=0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        try {
            Invoke-RestMethod -Uri "$baseUrl/v1/models" -TimeoutSec 5 | Out-Null
            $ok = $true; break
        } catch {}
    }
    if (-not $ok) { throw "Ollama did not become ready at $baseUrl" }
}

# Pull model if missing
try {
    $models = Invoke-RestMethod -Uri "$baseUrl/v1/models" -TimeoutSec 10
    $has = $false
    foreach ($m in $models.data) { if ($m.id -eq $Model) { $has = $true } }
    if (-not $has) {
        Write-Host "[info] Pulling model $Model ..."
        & ollama pull $Model
    } else {
        Write-Host "[info] Model $Model already available."
    }
} catch {
    Write-Warning "[warn] Could not verify models from $baseUrl: $_"
}

# Create temporary gh stub (PowerShell)
$Tmp = New-Item -ItemType Directory -Path ([System.IO.Path]::GetTempPath()) -Name ("ghstub_" + [System.Guid]::NewGuid().ToString("N"))
$StubDir = $Tmp.FullName
$OutDir = New-Item -ItemType Directory -Path (Join-Path $StubDir "out")
$LocalOut = Join-Path $OutDir "local-pr-comment.md"

# gh.ps1 stub
$ghStub = @"
param([Parameter(Mandatory=\$true)][string]\$cmd, [Parameter(ValueFromRemainingArguments=\$true)]\$args)
\$env:BASE_SHA = \$env:BASE_SHA
\$env:HEAD_SHA = \$env:HEAD_SHA
if (\$cmd -eq "api") {
  \$endpoint = \$args[0]
  if (\$endpoint -match '^repos/.*/pulls/.*/files') {
    if (-not \$env:BASE_SHA -or -not \$env:HEAD_SHA) { Write-Output "[]"; exit 0 }
    # Build JSON array from git diff name-status
    \$lines = git diff --name-status "\$env:BASE_SHA..\$env:HEAD_SHA"
    \$items = @()
    foreach (\$ln in \$lines) {
      if (-not \$ln) { continue }
      \$parts = \$ln -split "\t"
      if (\$parts.Length -lt 2) { continue }
      \$status = \$parts[0]
      if (\$status -like "R*") {
        \$file = \$parts[-1]
        \$mapped = "modified"
      } elseif (\$status -eq "A") {
        \$file = \$parts[1]; \$mapped = "added"
      } elseif (\$status -eq "M") {
        \$file = \$parts[1]; \$mapped = "modified"
      } elseif (\$status -eq "D") {
        \$file = \$parts[1]; \$mapped = "removed"
      } else {
        \$file = \$parts[-1]; \$mapped = "modified"
      }
      \$items += @{ filename = \$file; status = \$mapped }
    }
    \$json = (\$items | ConvertTo-Json -Compress)
    Write-Output \$json
    exit 0
  } else {
    Write-Output "{}"
    exit 0
  }
} elseif (\$cmd -eq "pr") {
  if (\$args[0] -eq "comment") {
    # find --body-file <path>
    \$bodyPath = \$null
    for (\$i=0; \$i -lt \$args.Count; \$i++) {
      if (\$args[\$i] -eq "--body-file" -and (\$i + 1) -lt \$args.Count) {
        \$bodyPath = \$args[\$i+1]; break
      }
    }
    if (\$bodyPath -and (Test-Path \$bodyPath)) {
      Copy-Item -Path \$bodyPath -Destination "$LocalOut" -Force
      Write-Output "[gh-stub] Wrote PR comment to $LocalOut"
    } else {
      Write-Output "[gh-stub] No body file found."
    }
    exit 0
  }
}
Write-Output "[gh-stub] Unsupported command: \$cmd \$args"
"@
$ghPath = Join-Path $StubDir "gh.ps1"
$ghStub | Out-File -FilePath $ghPath -Encoding UTF8 -Force

# Create a lightweight shim batch to call gh.ps1
$ghBat = @"
@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0gh.ps1" %*
"@
$ghBatPath = Join-Path $StubDir "gh.bat"
$ghBat | Out-File -FilePath $ghBatPath -Encoding ASCII -Force

# Put stub on PATH (Windows prefers .bat shim)
$env:PATH = "$StubDir;$env:PATH"
$originUrl = (git config --get remote.origin.url)
$repoSlug = $originUrl -replace '^(git@|https://)github\.com[:/ ]','' -replace '\.git$',''
if (-not $repoSlug) { $repoSlug = "local/repo" }

# Analyzer env
$env:BASE_SHA = $BASE_SHA
$env:HEAD_SHA = $HEAD_SHA
$env:LOCAL_OUTPUT = $LocalOut
$env:REPO = $repoSlug
$env:PULL_NUMBER = "0"
$env:SERVER_URL = "https://github.com"
$env:API_URL = "https://api.github.com"
$env:WORKFLOW_NAME = "PR Targeted Test Analyzer"

# Use local LLM (Ollama) via OpenAI-compatible API
$env:LLM_PROVIDER = "openai"
$env:LLM_BASE_URL = "http://127.0.0.1:11434/v1"
$env:LLM_MODEL = $Model
$env:LLM_API_KEY = ""

# Disable remote
$env:OPENROUTER_API_KEY = ""
$env:OPENROUTER_MODEL = ""

$env:ANALYZER_MAX_HIGHLIGHTS = "200"
$env:LLM_MAX_TOKENS = "800"
$env:FAIL_ON_TEST_FAILURE = ($FailOnTestFailure.ToString().ToLower())

Write-Host "[info] Running PrChangeTestAnalyzer with local OpenAI-compatible provider (Ollama)"
$rc = 0
try {
    & java scripts/PrChangeTestAnalyzer.java
} catch {
    $rc = 1
}

Write-Host "`n----------------------------------------"
if (Test-Path $LocalOut) {
    Write-Host "[info] Local PR comment saved to: $LocalOut"
    Write-Host "Preview (first 80 lines):"
    Write-Host "----------------------------------------"
    Get-Content $LocalOut -TotalCount 80 | ForEach-Object { Write-Host $_ }
    Write-Host "----------------------------------------"
} else {
    Write-Warning "[warn] No PR comment file produced."
}

exit $rc