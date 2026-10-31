$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $env:PORT) {
  $env:PORT = "8000"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Write-Error "Docker is required. Install and start Docker Desktop, then retry."
  exit 1
}

docker build --tag pm-mvp --file (Join-Path $ProjectRoot "Dockerfile") $ProjectRoot
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

$DockerArgs = @(
  "--rm", "--detach", "--name", "pm-mvp",
  "--publish", "$($env:PORT):8000",
  "--volume", "pm-data:/data",
  "--env", "DATABASE_PATH=/data/kanban.sqlite3"
)
$EnvFile = Join-Path $ProjectRoot ".env"
if (Test-Path $EnvFile) {
  $DockerArgs += @("--env-file", $EnvFile)
}

docker run @DockerArgs pm-mvp
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

Write-Output "Project Management MVP is running at http://localhost:$($env:PORT)"
