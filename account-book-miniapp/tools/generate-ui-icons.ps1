# Reproducible original UI icons. Uses Windows System.Drawing; no app dependency.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$outputDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../miniprogram/assets/icons'))
$null = New-Item -ItemType Directory -Path $outputDirectory -Force

function New-Points([float[]]$Coordinates) {
    $points = for ($i = 0; $i -lt $Coordinates.Length; $i += 2) {
        [Drawing.PointF]::new($Coordinates[$i], $Coordinates[$i + 1])
    }
    return ,([Drawing.PointF[]]$points)
}

foreach ($name in @('home', 'receipt', 'chart', 'user', 'gift', 'house')) {
    $variants = if ($name -in @('gift', 'house')) { @('') } else { @('', '-active') }
    foreach ($variant in $variants) {
        $bitmap = [Drawing.Bitmap]::new(96, 96)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.ScaleTransform(3, 3)
        $color = if ($variant) { '#079c67' } elseif ($name -in @('gift','house')) { '#1f2329' } else { '#60646d' }
        $pen = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml($color), 1.8)
        $pen.StartCap = $pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
        $pen.LineJoin = [Drawing.Drawing2D.LineJoin]::Round
        try {
            switch ($name) {
                { $_ -in @('home', 'house') } {
                    if ($variant) {
                        $path = [Drawing.Drawing2D.GraphicsPath]::new()
                        $path.AddPolygon((New-Points @(4,14, 16,4, 28,14, 28,28, 4,28)))
                        $brush = [Drawing.Drawing2D.LinearGradientBrush]::new([Drawing.PointF]::new(8,4), [Drawing.PointF]::new(27,29), [Drawing.ColorTranslator]::FromHtml('#06ce83'), [Drawing.ColorTranslator]::FromHtml('#1595e8'))
                        $graphics.FillPath($brush, $path)
                        $graphics.FillRectangle([Drawing.Brushes]::White, 13, 20, 6, 8)
                        $brush.Dispose(); $path.Dispose()
                    } else {
                        $graphics.DrawLines($pen, (New-Points @(3,15, 16,4, 29,15)))
                        $graphics.DrawLines($pen, (New-Points @(7,13, 7,28, 13,28, 13,20, 19,20, 19,28, 25,28, 25,13)))
                    }
                }
                'receipt' {
                    $graphics.DrawLines($pen, (New-Points @(7,4, 25,4, 25,28, 21,26, 17,28, 13,26, 7,28, 7,4)))
                    $graphics.DrawLine($pen, 12,10,20,10); $graphics.DrawLine($pen,12,15,20,15); $graphics.DrawLine($pen,12,20,17,20)
                }
                'chart' {
                    $graphics.DrawArc($pen, 4,7,22,22,0,270)
                    $graphics.DrawLines($pen, (New-Points @(15,7, 15,18, 26,18)))
                    $graphics.DrawArc($pen, 7,3,22,22,270,90)
                    $graphics.DrawLines($pen, (New-Points @(18,3, 18,14, 29,14)))
                }
                'user' {
                    $graphics.DrawEllipse($pen, 11,3,10,10)
                    $graphics.DrawArc($pen,5,18,22,20,180,180)
                    $graphics.DrawLine($pen,5,28,27,28)
                }
                'gift' {
                    $graphics.DrawRectangle($pen,4,12,24,6); $graphics.DrawRectangle($pen,6,18,20,11)
                    $graphics.DrawLine($pen,16,12,16,29)
                    $graphics.DrawBezier($pen,16,12,3,13,5,0,13,6)
                    $graphics.DrawLine($pen,13,6,16,12)
                    $graphics.DrawBezier($pen,16,12,29,13,27,0,19,6)
                    $graphics.DrawLine($pen,19,6,16,12)
                }
            }
            $bitmap.Save((Join-Path $outputDirectory ($name + $variant + '.png')), [Drawing.Imaging.ImageFormat]::Png)
        } finally { $pen.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
    }
}
Write-Output 'Generated 10 local UI icons.'
