@echo off
setlocal enabledelayedexpansion
REM Run PrChangeTestAnalyzer locally for a given commit range, using a local Ollama model (OpenAI-compatible API).
REM - Starts Ollama (if not running), ensures model is available.
REM - Stubs `gh` so the analyzer can read "PR files" from `git diff <base>..<head>` and "post comment" to a local file.
REM - Executes the analyzer with LLM_PROVIDER=openai to use the local model.
REM
REM Usage:
REM   scripts\run-local-commit-range-analysis.bat <BASE_SHA_OR_REF> <HEAD_SHA_OR_REF> [--model <ollama_model>] [--fail-on-test-failure <true|false>]
REM
REM Examples:
REM   scripts\run-local-commit-range_analysis.bat main HEAD
REM   scripts\run-local-commit-range_analysis.bat 1a2b3c4d 5e6f7a8b --model "llama3.1:8b-instruct"
REM
REM Requirements:
REM - git, curl, java (21+), and Maven/Gradle as needed by your project.
REM - Run at the repository root (where scripts\PrChangeTestAnalyzer.java exists).

if "%~2"=="" (
  echo Usage: %~nx0 ^<BASE_SHA_OR_REF^> ^<HEAD_SHA_OR_REF^> [--model ^<ollama_model^>] [--fail-on-test-failure ^<true^|false^>]
  exit /b 1
)

set "BASE_REF=%~1"
set "HEAD_REF=%~2"
shift
shift

set "OLLAMA_MODEL=llama3.1:8b-instruct"
set "FAIL_ON_TEST_FAILURE=true"

:argsloop
if "%~1"=="" goto argsdone
if /I "%~1"=="--model" (
  if "%~2"=="" (echo [error] --model requires a value & exit /b 1)
  set "OLLAMA_MODEL=%~2"
  shift
  shift
  goto argsloop
)
if /I "%~1"=="--fail-on-test-failure" (
  if "%~2"=="" (echo [error] --fail-on-test-failure requires a value & exit /b 1)
  set "FAIL_ON_TEST_FAILURE=%~2"
  shift
  shift
  goto argsloop
)
echo [error] Unknown argument: %~1
exit /b 1

:argsdone

REM Resolve SHAs
for /f "usebackq delims=" %%A in (`git rev-parse "%BASE_REF%"`) do set "BASE_SHA=%%A"
for /f "usebackq delims=" %%A in (`git rev-parse "%HEAD_REF%"`) do set "HEAD_SHA=%%A"
if not defined BASE_SHA (echo [error] Could not resolve BASE_REF %BASE_REF% & exit /b 1)
if not defined HEAD_SHA (echo [error] Could not resolve HEAD_REF %HEAD_REF% & exit /b 1)
echo [info] Commit range: %BASE_SHA% .. %HEAD_SHA%

REM Ensure Java
where java >nul 2>nul || (echo [error] java not found. Please install Java 21+ and retry. & exit /b 1)

REM Ensure curl
where curl >nul 2>nul || (echo [error] curl not found in PATH. Please install or use PowerShell script variant. & exit /b 1)

REM Ensure Ollama (OpenAI-compatible at http://127.0.0.1:11434/v1)
set "BASE_URL=http://127.0.0.1:11434"
curl -sSf "%BASE_URL%/v1/models" >nul 2>nul
if errorlevel 1 (
  where ollama >nul 2>nul || (echo [error] ollama not found. Install from https://ollama.com and retry. & exit /b 1)
  echo [info] Starting ollama serve ...
  start "" /b ollama serve
  set /a _wait=0
  :wait_ollama
  curl -sSf "%BASE_URL%/v1/models" >nul 2>nul && goto ollama_ready
  set /a _wait+=1
  if %_wait% GEQ 60 (echo [error] Ollama did not become ready at %BASE_URL% & exit /b 1)
  timeout /t 2 >nul
  goto wait_ollama
)
:ollama_ready
REM Pull model if missing
set "_models_json=%TEMP%\ollama_models_%RANDOM%%RANDOM%.json"
curl -sSf "%BASE_URL%/v1/models" > "%_models_json%" 2>nul
findstr /C:"\"id\":\"%OLLAMA_MODEL%\"" "%_models_json%" >nul 2>nul
if errorlevel 1 (
  echo [info] Pulling model %OLLAMA_MODEL% ...
  ollama pull "%OLLAMA_MODEL%"
) else (
  echo [info] Model %OLLAMA_MODEL% already available.
)
del /f /q "%_models_json%" >nul 2>nul

REM Create gh stub (PowerShell-backed) to emulate the gh CLI calls used by PrChangeTestAnalyzer
for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "[System.IO.Path]::GetTempPath()"`) do set "TMPDIR=%%D"
set "STUBDIR=%TMPDIR%ghstub_%RANDOM%%RANDOM%"
mkdir "%STUBDIR%" >nul 2>nul
mkdir "%STUBDIR%\out" >nul 2>nul
set "LOCAL_OUTPUT=%STUBDIR%\out\local-pr-comment.md"

REM gh.ps1
> "%STUBDIR%\gh.ps1" (
  echo param([Parameter(Mandatory = $true)][string]^$cmd,^
        [Parameter(ValueFromRemainingArguments = $true)]^$args^)
  echo ^$ErrorActionPreference = "Stop"
  echo if (^$cmd -eq "api") ^{
  echo ^  ^$endpoint = ^$args[0]
  echo ^  if (^$endpoint -match '^repos/.*/pulls/.*/files') ^{
  echo ^    if (-not ^$env:BASE_SHA -or -not ^$env:HEAD_SHA) { Write-Output "[]" ; exit 0 }
  echo ^    ^$lines = git diff --name-status "^$env:BASE_SHA..^$env:HEAD_SHA"
  echo ^    ^$items = @()
  echo ^    foreach (^$ln in ^$lines) ^{
  echo ^      if (-not ^$ln) { continue }
  echo ^      ^$parts = ^$ln -split "`t"
  echo ^      if (^$parts.Length -lt 2) { continue }
  echo ^      ^$status = ^$parts[0]
  echo ^      if (^$status -like "R*") { ^$file = ^$parts[-1] ; ^$mapped = "modified" }
  echo ^      elseif (^$status -eq "A") { ^$file = ^$parts[1] ; ^$mapped = "added" }
  echo ^      elseif (^$status -eq "M") { ^$file = ^$parts[1] ; ^$mapped = "modified" }
  echo ^      elseif (^$status -eq "D") { ^$file = ^$parts[1] ; ^$mapped = "removed" }
  echo ^      else { ^$file = ^$parts[-1] ; ^$mapped = "modified" }
  echo ^      ^$items += @{ filename = ^$file; status = ^$mapped }
  echo ^    ^}
  echo ^    ^$json = (^$items ^| ConvertTo-Json -Compress)
  echo ^    Write-Output ^$json
  echo ^    exit 0
  echo ^  ^} else ^{
  echo ^    Write-Output "{}"
  echo ^    exit 0
  echo ^  ^}
  echo ^} elseif (^$cmd -eq "pr") ^{
  echo ^  if (^$args[0] -eq "comment") ^{
  echo ^    ^$bodyPath = ^$null
  echo ^    for (^$i=0; ^$i -lt ^$args.Count; ^$i++) ^{
  echo ^      if (^$args[^$i] -eq "--body-file" -and (^$i + 1) -lt ^$args.Count) ^{ ^$bodyPath = ^$args[^$i+1]; break ^}
  echo ^    ^}
  echo ^    if (^$bodyPath -and (Test-Path ^$bodyPath)) ^{
  echo ^      Copy-Item -Path ^$bodyPath -Destination "%LOCAL_OUTPUT%" -Force
  echo ^      Write-Output "[gh-stub] Wrote PR comment to %LOCAL_OUTPUT%"
  echo ^    ^} else ^{
  echo ^      Write-Output "[gh-stub] No body file found."
  echo ^    ^}
  echo ^    exit 0
  echo ^  ^}
  echo ^}
  echo Write-Output "[gh-stub] Unsupported command: ^$cmd ^$args"
)

REM gh.bat wrapper
> "%STUBDIR%\gh.bat" (
  echo @echo off
  echo powershell -NoProfile -ExecutionPolicy Bypass -File "%%~dp0gh.ps1" %%*
)

set "PATH=%STUBDIR%;%PATH%"

REM Compute repo slug like owner/repo
for /f "usebackq delims=" %%U in (`git config --get remote.origin.url 2^>nul`) do set "ORIGIN_URL=%%U"
if not defined ORIGIN_URL set "REPO_SLUG=local/repo"
if not defined REPO_SLUG (
  for /f "usebackq delims=" %%S in (`
    powershell -NoProfile -Command ^
      "$u='%ORIGIN_URL%'; $u -replace '^(git@|https://)github\.com[:/ ]','' -replace '\.git$',''"
  `) do set "REPO_SLUG=%%S"
)
if not defined REPO_SLUG set "REPO_SLUG=local/repo"

REM Analyzer environment
set "BASE_SHA=%BASE_SHA%"
set "HEAD_SHA=%HEAD_SHA%"
set "REPO=%REPO_SLUG%"
set "PULL_NUMBER=0"
set "SERVER_URL=https://github.com"
set "API_URL=https://api.github.com"
set "WORKFLOW_NAME=PR Targeted Test Analyzer"

REM Use local LLM (Ollama) via OpenAI-compatible API
set "LLM_PROVIDER=openai"
set "LLM_BASE_URL=http://127.0.0.1:11434/v1"
set "LLM_MODEL=%OLLAMA_MODEL%"
set "LLM_API_KEY="

REM Disable remote
set "OPENROUTER_API_KEY="
set "OPENROUTER_MODEL="

set "ANALYZER_MAX_HIGHLIGHTS=200"
set "LLM_MAX_TOKENS=800"
set "FAIL_ON_TEST_FAILURE=%FAIL_ON_TEST_FAILURE%"

echo [info] Running PrChangeTestAnalyzer with local OpenAI-compatible provider (Ollama)
call java scripts\PrChangeTestAnalyzer.java
set "RC=%ERRORLEVEL%"

echo.
echo ----------------------------------------
if exist "%LOCAL_OUTPUT%" (
  echo [info] Local PR comment saved to: %LOCAL_OUTPUT%
  echo Preview (first 80 lines):
  echo ----------------------------------------
  powershell -NoProfile -Command "Get-Content -Path '%LOCAL_OUTPUT%' -TotalCount 80 ^| ForEach-Object { $_ }"
  echo ----------------------------------------
) else (
  echo [warn] No PR comment file produced.
)

exit /b %RC%