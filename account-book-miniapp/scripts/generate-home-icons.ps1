# Repository-owned vector primitives rendered to local PNGs; no remote fonts or runtime dependencies.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$iconDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../miniprogram/assets/icons'))
function Fill-Polygon([single[]] $coordinates) {
    $points = for ($index = 0; $index -lt $coordinates.Length; $index += 2) {
        [Drawing.PointF]::new($coordinates[$index], $coordinates[$index + 1])
    }
    $graphics.FillPolygon($brush, [Drawing.PointF[]] $points)
}
foreach ($name in @('gift', 'house', 'chart', 'tags', 'wallet', 'logout')) {
    $color = switch ($name) { 'tags' { '#F0AA22' } 'wallet' { '#20AD7A' } 'logout' { '#8A93A3' } default { '#5365ED' } }
    $bitmap = [Drawing.Bitmap]::new(96, 96)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.ScaleTransform(4, 4)
    $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($color))
    $pen = [Drawing.Pen]::new($brush, 1.9)
    $white = [Drawing.SolidBrush]::new([Drawing.Color]::White)
    try {
        switch ($name) {
            'gift' {
                $graphics.FillRectangle($brush, 3, 9, 18, 4)
                $graphics.FillRectangle($brush, 5, 14, 14, 7)
                $graphics.FillRectangle($white, 11, 9, 2, 12)
                $graphics.DrawEllipse($pen, 5, 3, 6, 5)
                $graphics.DrawEllipse($pen, 12, 3, 6, 5)
            }
            'house' {
                Fill-Polygon @(2,11, 12,2, 22,11, 19,11, 19,21, 5,21, 5,11)
                $graphics.FillRectangle($white, 10, 14, 4, 7)
            }
            'chart' {
                $graphics.FillPie($brush, 2, 4, 18, 18, 0, 270)
                $graphics.FillPie($brush, 5, 1, 18, 18, 270, 90)
            }
            'tags' {
                Fill-Polygon @(2,3, 11,3, 21,13, 12,22, 2,12)
                $graphics.FillEllipse($white, 5, 6, 3, 3)
                $graphics.DrawLine($pen, 15, 3, 23, 11)
            }
            'wallet' {
                $graphics.FillRectangle($brush, 2, 7, 20, 14)
                Fill-Polygon @(2,7, 2,4, 18,1, 18,5)
                $graphics.FillRectangle($white, 15, 11, 7, 6)
                $graphics.FillEllipse($brush, 16, 13, 2, 2)
            }
            'logout' {
                $graphics.DrawLine($pen, 10, 3, 3, 3)
                $graphics.DrawLine($pen, 3, 3, 3, 21)
                $graphics.DrawLine($pen, 3, 21, 10, 21)
                $graphics.DrawLine($pen, 9, 12, 21, 12)
                $graphics.DrawLine($pen, 16, 7, 21, 12)
                $graphics.DrawLine($pen, 21, 12, 16, 17)
            }
        }
        $bitmap.Save((Join-Path $iconDirectory "home-$name.png"), [Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $pen.Dispose(); $brush.Dispose(); $white.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
    }
}
