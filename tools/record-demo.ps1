# Records the Anulreong judge demo on the Pleos emulator (emulator only).
# Flow: start demo -> trip 1 record sick -> ventilate (console session) -> trip 2 alert
#       -> compare routes -> take comfortable road -> records tab.
# Fixed coordinates for the 2560x1440 Pleos display (160 dpi). Timing matters: the revisit
# banner is shown for 25 s and shifts the comparison card down while visible.
param([string]$Out = (Join-Path $PSScriptRoot '..\docs\demo.mp4'))
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'io.infotive.anulleong'
function Tap($x, $y) { & $adb -s emulator-5554 shell input tap $x $y }
function At($sec) { $w = $sec - $script:clock.Elapsed.TotalSeconds; if ($w -gt 0) { Start-Sleep -Milliseconds ([int]($w * 1000)) } }

if ((& $adb -s emulator-5554 shell getprop ro.hardware) -notmatch 'ranchu') { throw 'Emulator only.' }
& $adb -s emulator-5554 forward tcp:8088 tcp:8088 | Out-Null
$session = 'http://127.0.0.1:8088/browser/session?client=anulreong-demo'
$hb = Start-Job -ArgumentList $session -ScriptBlock {
    param($u)
    for ($i = 0; $i -lt 600; $i++) {
        try { Invoke-WebRequest -UseBasicParsing -Method Post -Uri $u -TimeoutSec 3 | Out-Null } catch {}
        Start-Sleep 1
    }
}
Start-Sleep 6
$baseline = Invoke-RestMethod -Uri 'http://127.0.0.1:8088/vehicle/state' -TimeoutSec 10

# fresh state: no records, demo off
& $adb -s emulator-5554 shell pm clear --user current $pkg | Out-Null
& $adb -s emulator-5554 shell am start --user current -n "$pkg/.MainActivity" | Out-Null
# Connecting NaviHelper wakes the navigation app, which takes the foreground once its map is
# loaded. Wait until that has happened, bring the app back, and require it to stay on top.
function TopIsApp { (& $adb -s emulator-5554 shell "dumpsys activity activities | grep -m1 topResumedActivity") -match [regex]::Escape($pkg) }
for ($i = 0; $i -lt 30; $i++) { Start-Sleep 3; if (-not (TopIsApp)) { Write-Output "navi took the foreground after $($i*3)s"; break } }
Start-Sleep 8
$stable = $false
for ($i = 0; $i -lt 6 -and -not $stable; $i++) {
    & $adb -s emulator-5554 shell am start --user current -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep 4
    $stable = $true
    for ($k = 0; $k -lt 5; $k++) { Start-Sleep 2; if (-not (TopIsApp)) { $stable = $false; break } }
}
if (-not $stable) { throw 'app did not stay in front' }
$webm = Join-Path $env:TEMP 'anulreong-demo.webm'
Remove-Item $webm -ErrorAction SilentlyContinue
# host-side recorder (the on-device screenrecord stopped early on this image)
& $adb -s emulator-5554 emu screenrecord start --time-limit 180 $webm | Out-Null
$script:clock = [Diagnostics.Stopwatch]::StartNew()
try {
    At 3;   Tap 2452 218    # 시연 시작
    At 25;  Tap 2350 571    # 멀미 나요 (trip 1, ~1.3 km)
    $chk = Invoke-RestMethod -Method Post -Uri $session -TimeoutSec 3
    Write-Output ("{0,5:N1}s console active={1}" -f $script:clock.Elapsed.TotalSeconds, $chk.active)
    At 30;  Tap 1693 1174   # 바람 넣기 (re-read from the car to confirm)
    At 31.5; Tap 1693 1174  # second tap is harmless (sheet is busy) but covers a slow open
    At 44;  Tap 1000 300    # close the result sheet
    At 47;  Tap 1869 412    # 다음 여행 시작 (trip 2) -> pin, alert at ~61 s
    At 72;  Tap 1870 412    # 덜 나는 길 찾기 (guide card button)
    At 92;  Tap 1875 845    # 편안한 길로 가기 (banner gone by now) -> trip 3, no alert
    At 112; Tap 1693 1263   # 기록 tab: sick-zone analysis
    At 124
} finally {
    & $adb -s emulator-5554 emu screenrecord stop | Out-Null
    Start-Sleep 4
    New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
    $ff = Get-ChildItem C:\ffmpeg-master-latest-win64-gpl-shared -Recurse -Filter ffmpeg.exe | Select-Object -First 1 -ExpandProperty FullName
    & $ff -v error -y -i $webm -vf "scale=1920:-2,fps=30" -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p -movflags +faststart $Out
    try {
        $rc = $baseline.hvac.recirc_on.ToString().ToLowerInvariant()
        Invoke-WebRequest -UseBasicParsing -Method Put -Uri "http://127.0.0.1:8088/vehicle/hvac?recirc=$rc" -TimeoutSec 10 | Out-Null
        $rr = $baseline.body.windows.rear_right
        Invoke-WebRequest -UseBasicParsing -Method Put -Uri "http://127.0.0.1:8088/vehicle/body?window_rr=$rr" -TimeoutSec 10 | Out-Null
    } catch { Write-Warning "restore: $_" }
    Stop-Job $hb; Remove-Job $hb
    try { Invoke-WebRequest -UseBasicParsing -Method Post -Uri ($session + '&closing=1') -TimeoutSec 5 | Out-Null } catch {}
    Write-Output "saved $Out"
}
