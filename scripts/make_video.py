#!/usr/bin/env python3
"""demo_scenario.sh(VIDEO=1)가 남긴 OUT/screen.mp4와 OUT/captions.tsv로 자막 입힌 시연 영상 OUT/baton_demo.mp4를 만든다.

사용법: FFMPEG=/경로/ffmpeg python3 scripts/make_video.py OUT
자막: 위 왼쪽 장면 제목, 아래 가운데 화면 설명, 아래 왼쪽 가상 콘솔 입력·시연용 자동 조작·음성 안내.
"""
import os, subprocess, sys

out = sys.argv[1]
rows = [l.rstrip("\n").split("\t", 2) for l in open(os.path.join(out, "captions.tsv"), encoding="utf-8") if l.strip()]
# 기기 녹화는 시작 표시보다 약 0.8초 늦게 첫 프레임을 쓴다(11/4 측정: 기어 표시 전환 0.06→0.9초, 7.52→8.3초)
t0 = next(float(t) for t, k, _ in rows if k == "start") - float(os.environ.get("OFFSET", "0.8"))
ev = [(float(t) - t0, k, x) for t, k, x in rows if k != "start" and float(t) >= t0]  # 녹화 전 준비 입력은 뺀다
end = max(t for t, _, _ in ev) + 6

def ts(s):
    s = max(s, 0)
    return "%d:%02d:%02d.%02d" % (s // 3600, s % 3600 // 60, s % 60, s * 100 % 100)

def until(i, kinds, cap):
    nxt = [t for t, k, _ in ev[i + 1:] if k in kinds]
    return min(nxt[0] if nxt else end, ev[i][0] + cap)

# 앱 화면을 1600x900으로 줄여 위에 두고, 아래 180px 띠에 자막을 모은다(자막이 앱 화면을 가리지 않게)
head = """[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Scene,Apple SD Gothic Neo,32,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
Style: Narr,Apple SD Gothic Neo,42,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,8,0,0,0,1
Style: Input,Apple SD Gothic Neo,30,&H0000E6FF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,8,0,0,0,1
Style: Note,Apple SD Gothic Neo,24,&H00B0B0B0,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,9,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
def dlg(a, b, style, pos, text):
    return "Dialogue: 0,%s,%s,%s,,0,0,0,,{\\pos(%d,%d)}%s" % (ts(a), ts(b), style, pos[0], pos[1], text)

lines = [dlg(0, end, "Note", (1890, 912), "차량 값: Pleos Connect 에뮬레이터 가상 콘솔 입력  |  화면: 실제 앱(release 빌드)")]
prefix = {"console": "가상 콘솔 입력  ", "user": "운전자 조작(시연용 자동 입력)  ", "voice": "(영상에는 소리 없음) "}
# 콘솔 입력은 1초 안에 연달아 온 것을 한 줄로 묶는다
inputs = []
for t, k, x in ev:
    if k in prefix:
        if inputs and inputs[-1][1] == k == "console" and t - inputs[-1][0] < 1.0:
            inputs[-1][2].append(x)
        else:
            inputs.append([t, k, [x]])
for j, (t, k, xs) in enumerate(inputs):
    stop = min(t + (6 if k == "voice" else 4), inputs[j + 1][0] if j + 1 < len(inputs) else end)
    lines.append(dlg(t, stop, "Input", (960, 1030), prefix[k] + " · ".join(xs)))
for i, (t, k, x) in enumerate(ev):
    if k == "scene":
        lines.append(dlg(t, until(i, {"scene"}, 999), "Scene", (30, 912), x))
    elif k == "narr":
        lines.append(dlg(t, until(i, {"narr", "scene"}, 10), "Narr", (960, 962), x))
ass = os.path.join(out, "captions.ass")
open(ass, "w", encoding="utf-8").write(head + "\n".join(lines) + "\n")
dst = os.path.join(out, "baton_demo.mp4")
subprocess.run([os.environ.get("FFMPEG", "ffmpeg"), "-y", "-loglevel", "error", "-i", os.path.join(out, "screen.mp4"),
                "-vf", "scale=1600:900,pad=1920:1080:160:0:black,subtitles=%s:fontsdir=/System/Library/Fonts" % ass, "-c:v", "libx264", "-crf", "20",
                "-pix_fmt", "yuv420p", "-an", dst], check=True)
print(dst)
