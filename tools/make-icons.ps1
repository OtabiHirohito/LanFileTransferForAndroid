param(
    [string]$Source = "",
    [double]$Scale = 0.62,
    [int]$Inset = 10
)

# Windows 版のアイコン (LanFileTransfer.png) を、そのまま Android の
# アダプティブアイコン用フォアグラウンドに収める。
#
# 以前の版は白いモチーフだけを抜き出し、背景をベクターのグラデーションで
# 再構成していた。そのため元画像の光沢や細かなグラデーションが失われ、
# モチーフの輪郭にも抽出由来のムラが出ていた。
#
# 現在は「元画像全体」(紫グラデーション + 光沢 + 白いモチーフ) を
# アダプティブアイコンの可視領域 (72dp / 108dp = 66.67%) に収まるよう
# 縮小してフォアグラウンドにしている。
#
# 背景は、フォアグラウンドの外周色をそのまま外側へ延長した画像。
# これにより、円形マスクの内側にフォアグラウンドの四角い境界や
# 別色のリングが見えない。元画像の左端で切れているケーブルも、
# アイコンの端まで自然に続く。
#
#   - res/drawable-nodpi/ic_launcher_foreground.png (元画像全体 / 432px)
#   - res/drawable-nodpi/ic_launcher_background.png (外周延長 / 432px)
#   - docs/app-icon-preview.png (実際の円形マスクを適用した確認用)
#
# 角丸の外側の余白と外周の細い枠線を除くため、指定ピクセルだけ内側を切り出す。
# 元画像は変更しない。

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices

$tools = $PSScriptRoot
$android = Split-Path -Parent $tools
$root = Split-Path -Parent $android
if (-not $Source) { $Source = Join-Path $root "LanFileTransfer.png" }

$res = Join-Path $android "app\src\main\res"
$outDir = Join-Path $res "drawable-nodpi"
$previewDir = Join-Path $android "docs"
New-Item -ItemType Directory -Force -Path $outDir, $previewDir | Out-Null

$src = New-Object System.Drawing.Bitmap $Source
$w = $src.Width
$h = $src.Height

$cropW = $w - $Inset * 2
$cropH = $h - $Inset * 2
if ($cropW -le 0 -or $cropH -le 0) {
    throw "Inset が大きすぎます。"
}

$canvas = 432
$fore = New-Object System.Drawing.Bitmap $canvas, $canvas, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gfx = [System.Drawing.Graphics]::FromImage($fore)
$gfx.Clear([System.Drawing.Color]::Transparent)
$gfx.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$gfx.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$gfx.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality

# アダプティブアイコンの可視領域 (72dp / 108dp = 66.67%) に収める。
# 62% にすることで、元画像のモチーフ (右端のプラグ、右下の書類) が
# Pixel の円形マスクでも欠けない。
$target = $canvas * $Scale
$drawScale = [Math]::Min($target / $cropW, $target / $cropH)
$drawW = [int][Math]::Round($cropW * $drawScale)
$drawH = [int][Math]::Round($cropH * $drawScale)
$destX = [int](($canvas - $drawW) / 2)
$destY = [int](($canvas - $drawH) / 2)

$srcRect = New-Object System.Drawing.Rectangle $Inset, $Inset, $cropW, $cropH
$destRect = New-Object System.Drawing.Rectangle $destX, $destY, $drawW, $drawH
$gfx.DrawImage($src, $destRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
$gfx.Dispose()

$forePath = Join-Path $outDir "ic_launcher_foreground.png"
$fore.Save($forePath, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host "wrote $forePath (${drawW}x${drawH} on ${canvas}x${canvas})"

# --- 背景: フォアグラウンド外周色の延長 -------------------------------------
# 透過ピクセルを、最も近い不透明ピクセルの色で埋める (多始点 BFS)。
# これで四角いフォアグラウンドの外周と背景が同じ色になり、境界が見えなくなる。
$rect = New-Object System.Drawing.Rectangle 0, 0, $canvas, $canvas
$readData = $fore.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $readData.Stride
$bytes = New-Object byte[] ($stride * $canvas)
[System.Runtime.InteropServices.Marshal]::Copy($readData.Scan0, $bytes, 0, $bytes.Length)
$fore.UnlockBits($readData)

# --- モノクロ (Android 13+ のテーマアイコン用) -----------------------------
# 前景の白いモチーフだけを白いシルエットとして残す。紫の背景は透明になる。
$mono = New-Object System.Drawing.Bitmap $canvas, $canvas, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$monoData = $mono.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$monoStride = $monoData.Stride
$monoBytes = New-Object byte[] ($monoStride * $canvas)

for ($y = 0; $y -lt $canvas; $y++) {
    for ($x = 0; $x -lt $canvas; $x++) {
        $i = $y * $stride + $x * 4
        $a = $bytes[$i + 3]
        if ($a -eq 0) { continue }
        $r = $bytes[$i + 2]
        $g = $bytes[$i + 1]
        $b = $bytes[$i]
        $lum = (0.2126 * $r + 0.7152 * $g + 0.0722 * $b) / 255.0
        $v = ($lum - 0.58) / 0.37
        if ($v -le 0) { continue }
        if ($v -gt 1) { $v = 1 }
        $mi = $y * $monoStride + $x * 4
        $monoBytes[$mi] = 255
        $monoBytes[$mi + 1] = 255
        $monoBytes[$mi + 2] = 255
        $monoBytes[$mi + 3] = [byte][Math]::Round(255 * $v * ($a / 255.0))
    }
}
[System.Runtime.InteropServices.Marshal]::Copy($monoBytes, 0, $monoData.Scan0, $monoBytes.Length)
$mono.UnlockBits($monoData)
$monoPath = Join-Path $outDir "ic_launcher_monochrome.png"
$mono.Save($monoPath, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host "wrote $monoPath (monochrome motif)"
$mono.Dispose()

$out = New-Object byte[] $bytes.Length
[Array]::Copy($bytes, $out, $bytes.Length)

$pixelCount = $canvas * $canvas
$visited = New-Object bool[] $pixelCount
$queue = New-Object 'System.Collections.Generic.Queue[int]'

for ($y = 0; $y -lt $canvas; $y++) {
    for ($x = 0; $x -lt $canvas; $x++) {
        $i = $y * $stride + $x * 4
        if ($bytes[$i + 3] -ge 250) {
            $visited[$y * $canvas + $x] = $true
            $queue.Enqueue($y * $canvas + $x)
        }
    }
}

while ($queue.Count -gt 0) {
    $idx = $queue.Dequeue()
    $x = $idx % $canvas
    $y = [int][Math]::Floor($idx / $canvas)
    $i = $y * $stride + $x * 4
    $b = $out[$i]
    $g = $out[$i + 1]
    $r = $out[$i + 2]

    for ($d = 0; $d -lt 4; $d++) {
        switch ($d) {
            0 { $nx = $x + 1; $ny = $y }
            1 { $nx = $x - 1; $ny = $y }
            2 { $nx = $x; $ny = $y + 1 }
            3 { $nx = $x; $ny = $y - 1 }
        }
        if ($nx -lt 0 -or $nx -ge $canvas -or $ny -lt 0 -or $ny -ge $canvas) { continue }
        $nidx = $ny * $canvas + $nx
        if ($visited[$nidx]) { continue }
        $visited[$nidx] = $true
        $ni = $ny * $stride + $nx * 4
        $out[$ni] = $b
        $out[$ni + 1] = $g
        $out[$ni + 2] = $r
        $out[$ni + 3] = 255
        $queue.Enqueue($nidx)
    }
}

# 中央の不透明領域 (元のモチーフを含む) は、フォアグラウンドで完全に隠れる。
# パララックス等で背景が覗いてもモチーフが二重に見えないよう、
# 四隅の色から作った滑らかなグラデーションで置き換える。
$tl = @(0xB0, 0x58, 0xEA)
$tr = @(0x96, 0x46, 0xD6)
$bl = @(0x8C, 0x38, 0xD3)
$br = @(0x73, 0x30, 0xC1)

for ($y = $destY; $y -lt $destY + $drawH; $y++) {
    for ($x = $destX; $x -lt $destX + $drawW; $x++) {
        $i = $y * $stride + $x * 4
        if ($bytes[$i + 3] -lt 250) { continue }
        $u = ($x - $destX) / [double]($drawW - 1)
        $v = ($y - $destY) / [double]($drawH - 1)
        $rr = (1 - $v) * ((1 - $u) * $tl[0] + $u * $tr[0]) + $v * ((1 - $u) * $bl[0] + $u * $br[0])
        $gg = (1 - $v) * ((1 - $u) * $tl[1] + $u * $tr[1]) + $v * ((1 - $u) * $bl[1] + $u * $br[1])
        $bb = (1 - $v) * ((1 - $u) * $tl[2] + $u * $tr[2]) + $v * ((1 - $u) * $bl[2] + $u * $br[2])
        $out[$i] = [byte][Math]::Round($bb)
        $out[$i + 1] = [byte][Math]::Round($gg)
        $out[$i + 2] = [byte][Math]::Round($rr)
        $out[$i + 3] = 255
    }
}

$bg = New-Object System.Drawing.Bitmap $canvas, $canvas, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$writeData = $bg.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
[System.Runtime.InteropServices.Marshal]::Copy($out, 0, $writeData.Scan0, [Math]::Min($out.Length, $writeData.Stride * $canvas))
$bg.UnlockBits($writeData)

$bgPath = Join-Path $outDir "ic_launcher_background.png"
$bg.Save($bgPath, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host "wrote $bgPath (edge-extended background)"

# --- 確認用プレビュー -------------------------------------------------------
# 実際のランチャーと同じ 72dp の円形マスクを適用して、最終的な見た目を確認する。
$preview = New-Object System.Drawing.Bitmap $canvas, $canvas, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$pg = [System.Drawing.Graphics]::FromImage($preview)
$pg.Clear([System.Drawing.Color]::Transparent)
$pg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$pg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$pg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

$diameter = [int][Math]::Round($canvas * 2 / 3)  # 72dp / 108dp
$offset = [int](($canvas - $diameter) / 2)
$clip = New-Object System.Drawing.Drawing2D.GraphicsPath
$clip.AddEllipse($offset, $offset, $diameter, $diameter)
$pg.SetClip($clip)

$pg.DrawImage($bg, 0, 0)
$pg.DrawImage($fore, 0, 0)
$pg.ResetClip()
$pg.Dispose()
$clip.Dispose()

$previewPath = Join-Path $previewDir "app-icon-preview.png"
$preview.Save($previewPath, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host "wrote $previewPath (circular mask, ${diameter}px)"

$preview.Dispose()
$bg.Dispose()
$fore.Dispose()
$src.Dispose()
