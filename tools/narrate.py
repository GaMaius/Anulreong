# Builds the demo video's narration track from the app's own speech requests.
# The Pleos emulator's on-device TTS needs up to ~30 s per sentence and its audio does not reach the
# PC, so every sentence the app handed to TextToSpeech (logged as "AnulVoice: speak: ...") is voiced
# with the Windows Korean voice at the moment the app requested it (queued like a real TTS engine).
# usage: python narrate.py voice_log.txt <start_epoch_ms> <device_minus_host_ms> <seconds> out.wav
import os, re, subprocess, sys, tempfile, wave
import numpy as np

log, start_ms, dev_off, secs, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
rate = 22050
lines = []
for raw in open(log, encoding='utf-8', errors='replace'):
    m = re.match(r'\s*(\d+\.\d+)\s.*AnulVoice: speak: (.+)$', raw.rstrip())
    if m:
        t = (float(m.group(1)) * 1000 - dev_off - start_ms) / 1000
        if 0 <= t < secs:
            lines.append((t, m.group(2)))

tmp = tempfile.mkdtemp()
ps = ['Add-Type -AssemblyName System.Speech', '$s = New-Object System.Speech.Synthesis.SpeechSynthesizer',
      "$s.SelectVoice('Microsoft Heami Desktop'); $s.Rate = 1",
      '$f = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(22050, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)']
for i, (_, text) in enumerate(lines):
    with open(os.path.join(tmp, f'{i}.txt'), 'w', encoding='utf-8') as f:
        f.write(text)
    ps.append(f"$s.SetOutputToWaveFile('{tmp}\\{i}.wav', $f); $s.Speak([IO.File]::ReadAllText('{tmp}\\{i}.txt', [Text.Encoding]::UTF8))")
ps.append('$s.Dispose()')
script = os.path.join(tmp, 'say.ps1')
with open(script, 'w', encoding='utf-8-sig') as f:
    f.write('\n'.join(ps))
subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script], check=True)

track = np.zeros(int((secs + 40) * rate), dtype=np.float32)
free_at = 0.0
for i, (t, text) in enumerate(lines):
    with wave.open(os.path.join(tmp, f'{i}.wav')) as w:
        clip = np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float32) / 32768
    at = max(t, free_at)                       # one sentence at a time, like the car's TTS queue
    a = int(at * rate)
    track[a:a + len(clip)] += clip
    free_at = at + len(clip) / rate + 0.3
    print(f'{t:6.1f}s -> {at:6.1f}s  {text}')
track = track[:int(secs * rate)]
with wave.open(out, 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
    w.writeframes((np.clip(track, -1, 1) * 32767).astype('<i2').tobytes())
print('narration', out, len(lines), 'sentences')
