# Captures the Anulreong judge demo on the Pleos emulator (2560x1440 @160dpi, emulator only) as a
# sequence of real screen captures and turns them into docs/demo.mp4 + docs/demo.gif.
# (Continuous screenrecord kept stopping early on this emulator image, so frames are grabbed
#  with screencap about once per second and stitched with their real timing.)
# Story: trip 1 — 🤢 after a roundabout → 환기 케어 (vehicle steps, re-read) → 원래대로
#        trip 2 — 선제 케어 before the recorded spot → revisit alert → 속편한 점수 비교 → 편안한 길
#        trip 3 — 유사 구간 예측 on a road never driven (roundabout) → 기록 탭 analysis
# Needs the Pleos Virtual Console session alive for vehicle writes (heartbeat job below).
param([string]$Docs = (Join-Path $PSScriptRoot '..\docs'), [int]$CompareX = 1987, [int]$CompareY = 913)
$ErrorActionPreference = 'Continue'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'io.infotive.anulleong'
$frames = Join-Path $env:TEMP 'anulreong-frames'
Remove-Item $frames -Recurse -Force -ErrorAction SilentlyContinue; New-Item -ItemType Directory -Force $frames | Out-Null
function Tap($x, $y) { & $adb -s emulator-5554 shell input tap $x $y 2>$null }
function At($sec) { $w = $sec - $script:clock.Elapsed.TotalSeconds; if ($w -gt 0) { Start-Sleep -Milliseconds ([int]($w * 1000)) } }

if ((& $adb -s emulator-5554 shell getprop ro.hardware) -notmatch 'ranchu') { throw 'Emulator only.' }
& $adb -s emulator-5554 forward tcp:8088 tcp:8088 | Out-Null
& $adb -s emulator-5554 shell svc power stayon true
$hb = Start-Job { for ($i = 0; $i -lt 900; $i++) { try { Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:8088/browser/session?client=anulreong-demo' -TimeoutSec 3 | Out-Null } catch {}; Start-Sleep 1 } }

& $adb -s emulator-5554 shell pm clear --user current $pkg 2>$null | Out-Null
& $adb -s emulator-5554 shell am start --user current -n "$pkg/.MainActivity" 2>$null | Out-Null
# Connecting NaviHelper brings the navigation app to the front once; wait for that, then make
# sure the app is back on top and stays there for 12 s before capturing.
function TopIsApp { ((& $adb -s emulator-5554 shell "dumpsys activity activities" 2>$null) -join "`n") -match ('topResumedActivity=\S+ \S+ ' + [regex]::Escape($pkg)) }
for ($i = 0; $i -lt 25; $i++) { Start-Sleep 3; if (-not (TopIsApp)) { Write-Output "navi took the foreground after $($i*3)s"; break } }
Start-Sleep 6
$stable = $false
for ($i = 0; $i -lt 8 -and -not $stable; $i++) {
    & $adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP
    & $adb -s emulator-5554 shell am start --user current -n "$pkg/.MainActivity" 2>$null | Out-Null
    Start-Sleep 3; $stable = $true
    for ($k = 0; $k -lt 6; $k++) { Start-Sleep 2; if (-not (TopIsApp)) { $stable = $false; break } }
    # the Pleos system UI sometimes restarts after boot and leaves the screen black: require a real frame
    $probe = Join-Path $frames 'probe.png'
    cmd /c "`"$adb`" -s emulator-5554 exec-out screencap -p > `"$probe`""
    if ($stable -and (Get-Item $probe).Length -lt 300kb) { Write-Output 'screen still blank, waiting'; $stable = $false; Start-Sleep 15 }
    Remove-Item $probe -ErrorAction SilentlyContinue
}
if (-not $stable) { Stop-Job $hb; Remove-Job $hb; throw 'app did not stay in front' }

$start = [DateTime]::UtcNow
$cap = Start-Job -ArgumentList $adb, $frames, $start -ScriptBlock { param($adb, $frames, $start)
    while ($true) {
        $ms = [int]([DateTime]::UtcNow - $start).TotalMilliseconds
        cmd /c "`"$adb`" -s emulator-5554 exec-out screencap -p > `"$frames\f_$('{0:D7}' -f $ms).png`""
        if (Test-Path "$frames\stop") { break }
    }
}
$script:clock = [Diagnostics.Stopwatch]::StartNew()
At 2;    Tap 1935 707      # 시연 시작
At 31;   Tap 2359 330      # 멀미 나요 (just after the roundabout)
At 35;   Tap 1693 1165     # 바람 넣기
At 36.5; Tap 1693 1165
At 54;   Tap 1693 1240     # 원래대로 돌리기
At 66;   Tap 1962 950      # 다음 여행 시작 (trip 2)
At 80;   Tap 1693 1165     # 바람 넣기 (선제 케어)
At 81.5; Tap 1693 1165
At 100;  Tap 1693 1165     # 확인
At 105;  Tap 1949 1120     # 덜 나는 길 찾기 (시연 안내 카드, 환기 카드 아래로 밀린 위치)
At 108;  Tap 1636 932      # 같은 버튼 (지난 멀미 알림 카드) – 이미 열렸으면 비교만 다시 계산
At 117;  Tap $CompareX $CompareY   # 편안한 길로 가기 (trip 3)
At 166;  Tap 907 302       # 기록 탭 (rail)
At 172;  & $adb -s emulator-5554 shell input swipe 1700 1100 1700 600 800 2>$null
At 179
New-Item -ItemType File "$frames\stop" | Out-Null
Wait-Job $cap -Timeout 20 | Out-Null; Remove-Job $cap -Force
Stop-Job $hb; Remove-Job $hb

# stitch with real timing (concat demuxer durations), crop the Pleos status/dock to the app window
$files = Get-ChildItem $frames -Filter 'f_*.png' | Where-Object { $_.Length -gt 50kb } | Sort-Object Name
$list = Join-Path $frames 'list.txt'
$lines = for ($i = 0; $i -lt $files.Count; $i++) {
    $t0 = [int]$files[$i].BaseName.Substring(2); $t1 = if ($i + 1 -lt $files.Count) { [int]$files[$i + 1].BaseName.Substring(2) } else { $t0 + 1000 }
    "file '$($files[$i].Name)'"; "duration $([math]::Round(($t1 - $t0) / 1000, 3))"
}
($lines + "file '$($files[-1].Name)'") | Set-Content -Encoding ascii $list
$ff = Get-ChildItem C:\ -Directory -Filter 'ffmpeg-master*' | Select-Object -First 1 | ForEach-Object { Get-ChildItem $_.FullName -Recurse -Filter ffmpeg.exe } | Select-Object -First 1 -ExpandProperty FullName
New-Item -ItemType Directory -Force $Docs | Out-Null
Push-Location $frames
& $ff -v error -y -f concat -safe 0 -i list.txt -vf "crop=1708:1210:852:90,scale=1708:-2,fps=30,format=yuv420p" -c:v libx264 -preset medium -crf 22 -movflags +faststart (Join-Path $Docs 'demo.mp4')
& $ff -v error -y -i (Join-Path $Docs 'demo.mp4') -vf "fps=6,scale=760:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer" (Join-Path $Docs 'demo.gif')
Pop-Location
Write-Output ("frames={0} saved {1}" -f $files.Count, (Join-Path $Docs 'demo.mp4'))
