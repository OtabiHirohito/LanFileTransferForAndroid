<#
.SYNOPSIS
  Android 版と Windows 版プロトコル実装の相互接続テストを 1 コマンドで実行する。

.DESCRIPTION
  1. tools/LftPeer (Windows 版の SecureChannel / TransferProtocol をそのままリンク) をビルド
  2. ホスト側で受信ピアを起動 (ポート 5061)
  3. ホスト側から端末へ送るピアをリトライ付きで起動 (adb forward 経由 / ポート 5062)
  4. app の計測テスト (DeviceInteropTest) を実行
  5. 両方向の受信内容と件数を検証

  エミュレータ (10.0.2.2) を前提にしている。実機の場合は -Serial を指定する
  (adb reverse が使える環境が必要)。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\tools\interop-test.ps1
#>
param(
    [string]$Serial = "",
    [switch]$KeepArtifacts
)

$ErrorActionPreference = "Stop"

$androidRoot = Split-Path -Parent $PSScriptRoot
$peerProject = Join-Path $PSScriptRoot "LftPeer\LftPeer.csproj"
$work = Join-Path $env:TEMP ("lft-interop-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
$receivedFromDevice = Join-Path $work "from-device"
$receivedFromHost = Join-Path $work "from-host"

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"

if (-not $env:JAVA_HOME) {
    $jbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
}

$receiver = $null
$senderJob = $null
$forwardAdded = $false
$reverseAdded = $false
$failed = $false

function Write-Step([string]$message) {
    Write-Host ""
    Write-Host "== $message" -ForegroundColor Cyan
}

function Wait-ForLog([string]$path, [string]$pattern, [int]$timeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $path) {
            $text = Get-Content $path -Raw -ErrorAction SilentlyContinue
            if ($text -and $text -match $pattern) { return $true }
        }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

try {
    New-Item -ItemType Directory -Force -Path $work, $receivedFromDevice, $receivedFromHost | Out-Null

    Write-Step "adb / デバイスの確認"
    $devices = & $adb devices | Select-String "device$"
    if (-not $devices) { throw "adb に接続されたデバイスがありません。" }

    if (-not $Serial) {
        $Serial = ($devices | Select-Object -First 1).Line.Split("`t")[0]
    }
    Write-Host "device: $Serial"

    $deviceHost = "10.0.2.2"
    if (-not $Serial.StartsWith("emulator-")) {
        Write-Host "実機のため adb reverse を設定します (device -> host)"
        & $adb -s $Serial reverse tcp:5061 tcp:5061 | Out-Null
        $reverseAdded = $true
        $deviceHost = "127.0.0.1"
    }

    Write-Step "ホスト側ピアのビルド"
    & dotnet build $peerProject -c Release --nologo -v q
    if ($LASTEXITCODE -ne 0) { throw "LftPeer のビルドに失敗しました。" }

    Write-Step "ホスト側 受信ピアを起動 (5061)"
    $receiverLog = Join-Path $work "receiver.log"
    $receiver = Start-Process dotnet -ArgumentList @(
        "run", "-c", "Release", "--no-build", "--project", $peerProject, "--",
        "serve", "5061", $receivedFromDevice, "合言葉パス123"
    ) -RedirectStandardOutput $receiverLog -PassThru -NoNewWindow

    if (-not (Wait-ForLog $receiverLog "LISTENING")) {
        throw "ホスト側ピアが待受を開始しませんでした。"
    }

    Write-Step "ホスト側 送信ピアの準備 (adb forward 5062 -> device 5062)"
    & $adb -s $Serial forward tcp:5062 tcp:5062 | Out-Null
    $forwardAdded = $true

    $hostSendFile = Join-Path $work "windows-to-device.bin"
    $bytes = New-Object byte[] 1000000
    for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = 65 } # 'A'
    [IO.File]::WriteAllBytes($hostSendFile, $bytes)

    $hostSendLog = Join-Path $work "host-send.log"
    $senderJob = Start-Job -ScriptBlock {
        param($project, $file, $log)
        Set-Location (Split-Path -Parent $project)
        for ($attempt = 1; $attempt -le 120; $attempt++) {
            $out = & dotnet run -c Release --no-build --project $project -- send 127.0.0.1 5062 $file "受信側パス" 2>&1
            $text = ($out | Out-String)
            Add-Content -Path $log -Value "[$attempt] $text"
            if ($text -match "SENT") { return "SENT" }
            Start-Sleep -Seconds 2
        }
        return "FAILED"
    } -ArgumentList $peerProject, $hostSendFile, $hostSendLog

    Write-Step "計測テストの実行"
    Push-Location $androidRoot
    try {
        & .\gradlew.bat :app:connectedDebugAndroidTest --console=plain `
            "-Pandroid.testInstrumentationRunnerArguments.interopHost=$deviceHost"
        $testExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    if ($testExit -ne 0) { $failed = $true }

    Write-Step "ホスト側 送信ピアの完了待ち"
    $senderResult = "TIMEOUT"
    if ($senderJob) {
        $completed = Wait-Job $senderJob -Timeout 240
        if ($completed) { $senderResult = (Receive-Job $senderJob) }
    }
    Write-Host "host sender: $senderResult"

    if ($senderResult -ne "SENT") { $failed = $true }

    Write-Step "結果の検証"
    if (-not (Wait-ForLog $receiverLog "RECEIVED device-to-windows.bin" 30)) {
        Write-Host "ホスト側で Android からの受信を確認できませんでした。" -ForegroundColor Red
        $failed = $true
    }
    else {
        $received = Get-ChildItem $receivedFromDevice -File | Where-Object { $_.Name -eq "device-to-windows.bin" }
        if (-not $received) {
            $failed = $true
        }
        else {
            $allB = $true
            $stream = [IO.File]::OpenRead($received.FullName)
            try {
                $buffer = New-Object byte[] 65536
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    for ($i = 0; $i -lt $read; $i++) {
                        if ($buffer[$i] -ne 66) { $allB = $false; break }
                    }
                    if (-not $allB) { break }
                }
            }
            finally { $stream.Close() }

            $sizeOk = $received.Length -eq 1500000
            Write-Host ("Android -> Windows: {0} bytes, all-B={1}" -f $received.Length, $allB)
            if (-not ($sizeOk -and $allB)) { $failed = $true }
        }
    }

    if (Test-Path $hostSendLog) {
        $sent = Get-Content $hostSendLog -Raw
        Write-Host ("Windows -> Android: {0}" -f ($(if ($sent -match "SENT") { "SENT" } else { "FAILED" })))
    }

    Write-Step "テスト結果"
    $results = Join-Path $androidRoot "app\build\outputs\androidTest-results\connected\debug"
    if (Test-Path $results) {
        Get-ChildItem $results -Filter *.xml | ForEach-Object { Write-Host $_.FullName }
    }

    if ($failed) {
        Write-Host "相互接続テスト: 失敗" -ForegroundColor Red
        exit 1
    }

    Write-Host "相互接続テスト: 成功 (Android <-> Windows 双方向・暗号化あり)" -ForegroundColor Green
}
catch {
    Write-Host ("エラー: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
finally {
    if ($senderJob) {
        Stop-Job $senderJob -ErrorAction SilentlyContinue
        Remove-Job $senderJob -Force -ErrorAction SilentlyContinue
    }
    if ($receiver -and -not $receiver.HasExited) {
        Stop-Process -Id $receiver.Id -Force -ErrorAction SilentlyContinue
    }
    if ($forwardAdded) { & $adb -s $Serial forward --remove tcp:5062 2>$null }
    if ($reverseAdded) { & $adb -s $Serial reverse --remove tcp:5061 2>$null }

    if (-not $KeepArtifacts) {
        Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    }
    else {
        Write-Host "作業ディレクトリ: $work"
    }
}
