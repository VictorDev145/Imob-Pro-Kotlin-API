$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $repoRoot

function New-UrlSafeSecret {
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker não foi encontrado. Instale o Docker Desktop e abra-o antes de continuar."
}

try {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker Engine indisponível" }
} catch {
    throw "O Docker Desktop não está pronto. Abra o Docker Desktop, aguarde o Engine iniciar e execute este script novamente."
}

docker compose version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose v2 não está disponível nesta instalação."
}

if (-not (Test-Path ".env")) {
    if (-not (Test-Path ".env.example")) { throw "Arquivo .env.example não encontrado." }
    $envContent = [System.IO.File]::ReadAllText((Join-Path $repoRoot ".env.example"))
    $envContent = [regex]::Replace($envContent, "(?m)^BFF_SESSION_SECRET=.*$", "BFF_SESSION_SECRET=$(New-UrlSafeSecret)")
    $envContent = [regex]::Replace($envContent, "(?m)^REDIS_PASSWORD=.*$", "REDIS_PASSWORD=$(New-UrlSafeSecret)")
    [System.IO.File]::WriteAllText((Join-Path $repoRoot ".env"), $envContent, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Arquivo .env criado com segredos locais aleatórios." -ForegroundColor Green
} else {
    Write-Host "Arquivo .env existente preservado." -ForegroundColor Yellow
}

Write-Host "Validando configuração Docker Compose..." -ForegroundColor Cyan
docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw "A configuração do Docker Compose é inválida. Revise o erro acima." }

Write-Host "Construindo e iniciando PostgreSQL, Redis, API Kotlin, interface e BFF..." -ForegroundColor Cyan
docker compose up --build -d
if ($LASTEXITCODE -ne 0) {
    docker compose ps
    docker compose logs --tail 80
    throw "Falha ao iniciar a stack. Os logs acima mostram o motivo."
}

$healthUrl = "http://localhost:3100/healthz"
$siteUrl = "http://localhost:3100/"
$ready = $false
for ($attempt = 1; $attempt -le 60; $attempt++) {
    try {
        $health = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 5
        $page = Invoke-WebRequest -Uri $siteUrl -UseBasicParsing -TimeoutSec 5
        if ($health.StatusCode -eq 200 -and $page.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 5
    }
}

if (-not $ready) {
    docker compose ps
    docker compose logs --tail 100 compat-bff api legacy-web
    throw "A stack iniciou, mas a página e/ou healthcheck não respondeu em até 5 minutos."
}

Write-Host ""
Write-Host "Imob-Pro está respondendo localmente: $siteUrl" -ForegroundColor Green
Write-Host "Healthcheck BFF: $healthUrl" -ForegroundColor Green
Write-Host "Para encerrar: docker compose down" -ForegroundColor Yellow
Start-Process $siteUrl
