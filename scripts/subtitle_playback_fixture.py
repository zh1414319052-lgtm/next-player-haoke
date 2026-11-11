"""Generate synthetic Next Player subtitle playback samples with FFmpeg."""
import argparse
from pathlib import Path
import shutil
import subprocess


ASS = r"""[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Chinese,Noto Sans SC,26,&H0000FF00,&H0000FF00,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
Style: Japanese,Noto Sans SC,19,&H0000FFFF,&H0000FFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
Style: Sign,Noto Sans SC,18,&H00FFAA00,&H00FFAA00,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,8,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:10.00,Chinese,,0,0,0,,中文：今天一起看电影
Dialogue: 0,0:00:01.00,0:00:10.00,Japanese,,0,0,0,,日本語：今日は一緒に映画を見よう
Dialogue: 1,0:00:01.00,0:00:10.00,Sign,,0,0,0,,{\pos(320,24)}上方标牌
Dialogue: 0,0:00:12.00,0:00:17.00,Chinese,,0,0,0,,中文：拖动后的第二句\N日本語：次のセリフ
"""

SRT = """1
00:00:01,000 --> 00:00:10,000
普通 SRT：中日两行
日本語：字幕のテスト

2
00:00:12,000 --> 00:00:17,000
普通 SRT：拖动后的第二句
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=True)
    sidecar = args.destination / "sidecar.mp4"
    subprocess.run([
        args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=0x222630:s=640x360:r=24:d=18",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(sidecar),
    ], check=True)
    shutil.copyfile(sidecar, args.destination / "manual.mp4")
    (args.destination / "sidecar.ASS").write_text(ASS, encoding="utf-8-sig")
    compact_ass = ASS.replace("\n\n[V4+ Styles]", "\n[V4+ Styles]").replace("\n\n[Events]", "\n[Events]")
    (args.destination / "manual-bilingual.ASS").write_text(compact_ass, encoding="utf-8-sig")
    (args.destination / "manual-plain.srt").write_text(SRT, encoding="utf-8")


if __name__ == "__main__":
    main()
