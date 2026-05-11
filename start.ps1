# VideoMind one-click startup script for Windows PowerShell
# Order: ngrok -> Spring Boot backend -> Vite frontend
# Usage:  .\start.ps1            (default profile=local)
#         .\start.ps1 -SpringProfile dev

param(
    [string]$SpringProfile = "local"
)

$ROOT  = $PSScriptRoot
$FRONT = Join-Path $ROOT "frontend"
$NGROK_API = "http://127.0.0.1:4040/api/tunnels"

function Step($msg) { Write-Host "`n>>> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "    [WARN] $msg" -ForegroundColor Yellow }

# Step 0: read minio.publicUrl from application-local.yml
Step "Read minio.publicUrl"

$localYml = Join-Path $ROOT "src\main\resources\application-local.yml"
$ngrokDomain = $null

if (Test-Path $localYml) {
    $match = Select-String -Path $localYml -Pattern 'publicUrl\s*:\s*"?(https?://[^"\s#]+)"?' | Select-Object -First 1
    if ($match -and $match.Matches.Count -gt 0) {
        $ngrokDomain = $match.Matches[0].Groups[1].Value.TrimEnd('/')
    }
}

if (-not $ngrokDomain -or $ngrokDomain -eq "http://localhost:19000") {
    Warn "minio.publicUrl 未配置公网地址，视频 ASR 功能将不可用。"
    Warn "如需视频功能，请在 application-local.yml 设置 minio.publicUrl 后重新运行。"
} else {
    Ok ("ngrok target: " + $ngrokDomain)
}

# Step 1: check Docker containers
Step "Check Docker containers"

$dockerOutput = docker ps 2>&1
$dockerText = [string]::Join(" ", $dockerOutput)
$required = @("mysql", "redis", "kafka", "es", "minio")
$missing = @()
foreach ($svc in $required) {
    if ($dockerText -notmatch $svc) { $missing += $svc }
}
if ($dockerText -notmatch "CONTAINER ID") {
    Warn "Docker 未就绪，请先启动 Docker Desktop。"
} elseif ($missing.Count -gt 0) {
    Warn ("以下容器未运行: " + ($missing -join ', '))
    Warn "请先执行: cd docs; docker compose up -d"
    $answer = Read-Host "    是否仍继续? (y/N)"
    if ($answer -ne 'y' -and $answer -ne 'Y') { exit 1 }
} else {
    Ok "Docker 容器检查通过"
}

# Step 2: start ngrok if domain configured and not running
if ($ngrokDomain) {
    Step "Start ngrok"

    $tunnelActive = $false
    try {
        $tunnels = Invoke-RestMethod -Uri $NGROK_API -ErrorAction Stop
        if ($tunnels.tunnels -and $tunnels.tunnels.Count -gt 0) {
            $existingUrl = $tunnels.tunnels[0].public_url
            Ok ("ngrok 已运行: " + $existingUrl)
            $tunnelActive = $true
        }
    } catch {
        # ngrok not running yet
    }

    if (-not $tunnelActive) {
        $domainOnly = $ngrokDomain -replace '^https?://', ''
        Write-Host ("    Launching: ngrok http --url=" + $domainOnly + " 19000") -ForegroundColor Gray
        Start-Process -FilePath "ngrok" -ArgumentList "http", "--url=$domainOnly", "19000" -WindowStyle Minimized

        $ready = $false
        for ($i = 0; $i -lt 15; $i++) {
            Start-Sleep -Seconds 1
            try {
                $tunnels = Invoke-RestMethod -Uri $NGROK_API -ErrorAction Stop
                if ($tunnels.tunnels -and $tunnels.tunnels.Count -gt 0) {
                    Ok ("ngrok ready: " + $tunnels.tunnels[0].public_url)
                    $ready = $true
                    break
                }
            } catch {}
        }
        if (-not $ready) {
            Warn "ngrok 15 秒未就绪，请手动检查 (http://127.0.0.1:4040)"
        }
    }
}

# Step 3: start Spring Boot backend in new window
Step ("Start backend (profile: " + $SpringProfile + ")")

$port8081 = netstat -ano | Select-String "LISTENING" | Select-String ":8081 "
if ($port8081) {
    Warn "端口 8081 已占用，跳过后端启动。"
} else {
    # PowerShell 会把 `-Dspring-boot.run.profiles=local` 误解为 -D 参数；用 --% 停止 PowerShell 解析，原样传给 mvn。
    $backendCmd = "Set-Location '" + $ROOT + "'; mvn --% spring-boot:run -Dspring-boot.run.profiles=" + $SpringProfile
    Start-Process powershell -ArgumentList "-NoExit", "-Command", $backendCmd -WindowStyle Normal
    Ok "后端启动中（新窗口）"
}

# Step 4: start frontend in new window
Step "Start frontend"

$port9527 = netstat -ano | Select-String "LISTENING" | Select-String ":9527 "
if ($port9527) {
    Warn "端口 9527 已占用，跳过前端启动。"
} else {
    # 预置 CI=true 避免 pnpm 在 TTY 缺失时弹"确认删 node_modules"导致退出。
    $frontCmd = "Set-Location '" + $FRONT + "'; `$env:CI='true'; pnpm dev"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", $frontCmd -WindowStyle Normal
    Ok "前端启动中（新窗口）"
}

# Done
Write-Host ""
Write-Host "===============================================" -ForegroundColor Cyan
Write-Host "  VideoMind 启动完成，等待各服务就绪：" -ForegroundColor Cyan
Write-Host "  Frontend: http://localhost:9527" -ForegroundColor White
Write-Host "  Backend:  http://localhost:8081" -ForegroundColor White
if ($ngrokDomain) {
    Write-Host "  ngrok:    http://127.0.0.1:4040" -ForegroundColor White
}
Write-Host "  Login: admin / admin123" -ForegroundColor White
Write-Host "===============================================" -ForegroundColor Cyan
