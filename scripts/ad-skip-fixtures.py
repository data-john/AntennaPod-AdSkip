import argparse
import hashlib
import json
import struct
import wave
from pathlib import Path


def generate(directory):
    directory.mkdir(parents=True, exist_ok=True)
    audio = directory / "seek-fixture.wav"
    sample_rate = 16000
    duration_ms = 16000
    intervals = [(0, 1000), (2000, 4000), (8000, 10000), (14000, 16000)]
    frames = bytearray()
    for sample in range(sample_rate * duration_ms // 1000):
        position_ms = sample * 1000 // sample_rate
        marked = any(start <= position_ms < end for start, end in intervals)
        period = 16 if marked else 40
        value = 2500 if sample % period < period // 2 else -2500
        frames.extend(struct.pack("<h", value))
    with wave.open(str(audio), "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(sample_rate)
        writer.writeframes(frames)
    sha256 = hashlib.sha256(audio.read_bytes()).hexdigest()
    bounds = [(0, 1000), (2000, 3000), (2500, 4000), (8000, 9000), (9000, 10000), (14000, 16000)]
    ranges = [{"range_id": f"fixture-{index}", "source": "controlled_fixture",
               "start_ms": start, "end_ms": end}
              for index, (start, end) in enumerate(bounds)]
    result = {
        "schema_version": "promotion-analysis-result-v1",
        "job_id": "controlled-seek-fixture",
        "media_sha256": sha256,
        "duration_ms": duration_ms,
        "runtime_identity": {"fixture": True},
        "ranges": ranges,
        "provenance_ranges": [dict(item) for item in ranges],
        "source_primary_labels": [],
        "calibrated_confidence": None,
        "outcome": "valid_ranges",
        "attempted_calls": 0,
        "valid_calls": 0,
        "playback_policy": "consumer_controlled",
        "self_promotion_acceptable_to_skip": True,
        "legacy_auto_skip_enabled": False,
        "expires_at": "2099-01-01T00:00:00Z",
    }
    cases = [
        {"position_ms": position, "target_ms": target}
        for position, target in [(0, 1000), (999, 1000), (1000, -1), (1999, -1), (2000, 4000),
                                 (2999, 4000), (3999, 4000), (4000, -1), (8000, 10000),
                                 (9999, 10000), (10000, -1), (14000, 16000), (15999, 16000), (16000, -1)]
    ]
    manifest = {"audio": audio.name, "media_sha256": sha256, "duration_ms": duration_ms,
                "merged_ranges": [[start, end] for start, end in intervals], "seek_cases": cases}
    for name, value in [("result.json", result), ("manifest.json", manifest)]:
        (directory / name).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", nargs="?", type=Path, default=Path("artifacts/ad-skip/m1/fixtures"))
    generate(parser.parse_args().output)
