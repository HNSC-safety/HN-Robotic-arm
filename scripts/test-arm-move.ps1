param(
    [string]$Base = "http://127.0.0.1:9988",
    [int]$Speed = 5
)

function Invoke-Api {
    param(
        [string]$Method = "GET",
        [string]$Path,
        [string]$Json
    )
    try {
        $uri = [Uri]("$Base$Path")
        if ($Method -eq "GET") {
            return (Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 30)
        }
        if ($Json) {
            return (Invoke-RestMethod -Uri $uri -Method Post -Body $Json `
                    -ContentType "application/json; charset=utf-8" -TimeoutSec 30)
        }
        return (Invoke-RestMethod -Uri $uri -Method Post -TimeoutSec 30)
    }
    catch {
        return @{ __error = $_.Exception.Message }
    }
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " 机械臂移动测试  ->  $Base" -ForegroundColor Cyan
Write-Host " 速度档位        ->  $Speed" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

Write-Host ""
Write-Host "[第 0 步] 连通性自检 (不通就别往下走了)" -ForegroundColor Yellow
Write-Host "--------------------------------------------------"
$check = Invoke-Api -Path "/api/ice/check"
Write-Host ($check | ConvertTo-Json -Depth 6)

if ($check.allUp -eq $true) {
    Write-Host "OK  算法端(10000) 可达" -ForegroundColor Green
}
else {
    Write-Host "ERR 算法端(10000) 不可达 -- 看上面的 status 字段：" -ForegroundColor Red
    Write-Host "   CONNECTION_REFUSED  -> 对端没启动，或端口不是 10000"
    Write-Host "   IDENTITY_NOT_FOUND  -> 暗号不一致，改 application.yaml 的 ice.client.*-identity"
    Write-Host "   TYPE_MISMATCH       -> 对端实现的是别的接口，看 ids 字段"
}

Write-Host ""
Write-Host "[第 1 步] 四个方向各发一次 MoveTask" -ForegroundColor Yellow
Write-Host "--------------------------------------------------"
foreach ($dir in @("Up", "Down", "Left", "Right")) {
    Write-Host ""
    Write-Host ">>> $dir (speed=$Speed)"
    $resp = Invoke-Api -Method "POST" -Path "/api/arm/move" -Json ('{"direction":"' + $dir + '","speed":' + $Speed + '}')
    Write-Host ("    响应: " + ($resp | ConvertTo-Json -Depth 4 -Compress))
    if ($resp.code -eq "EECSuccess") {
        Write-Host "    OK  对端 servant 已收到 MoveTask($dir, $Speed)" -ForegroundColor Green
    }
    else {
        Write-Host "    ERR 失败 -- 对端没收到或返回了错误码" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "[第 2 步] 本地回调端口(10001)自检" -ForegroundColor Yellow
Write-Host "--------------------------------------------------"
Write-Host ((Invoke-Api -Path "/api/ice/check/local") | ConvertTo-Json -Depth 4)

Write-Host ""
Write-Host "[第 3 步] 查看收到的回调事件" -ForegroundColor Yellow
Write-Host "--------------------------------------------------"
Write-Host ((Invoke-Api -Path "/api/events") | ConvertTo-Json -Depth 6)

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " 完成。若对端是真实 C# 算法端，请同时看它的控制台，" -ForegroundColor Cyan
Write-Host " 确认它打印出了对应的 MoveTask 指令。" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
