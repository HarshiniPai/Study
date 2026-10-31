$ErrorActionPreference = "Stop"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Write-Error "Docker is required. Install and start Docker Desktop, then retry."
  exit 1
}

docker stop pm-mvp
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}
