import array
import math
import subprocess
import sys
import wave
from pathlib import Path

FFMPEG = str(Path(sys.argv[1]).resolve())
out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
rate = 48000
freqs = [271, 383, 503, 83, 719, 887, 1061, 1291]

def run(*args):
    subprocess.run([FFMPEG, '-hide_banner', '-nostdin', '-y', *map(str, args)], check=True)

def crc16(data):
    crc = 0
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            polynomial = 0x8005 if crc & 0x8000 else 0
            crc = ((crc << 1) ^ polynomial) & 0xffff
    return crc

def verify_syncframes(path, channels):
    data = path.read_bytes()
    offset = 0
    access_units = 0
    expected_stream_type = 0
    while offset < len(data):
        location = (path.name, offset)
        header = data[offset:offset + 10]
        assert len(header) == 10 and header[:2] == b'\x0b\x77', ('invalid syncword', location)
        size = 2 * ((int.from_bytes(header[2:4], 'big') & 0x7ff) + 1)
        assert 10 <= size <= len(data) - offset, ('invalid frame size', location, size)
        assert header[4] >> 4 == 3, ('expected 48 kHz and six blocks', location)
        stream_type = header[2] >> 6
        assert stream_type == expected_stream_type, ('invalid substream order', location, stream_type)
        if stream_type == 1:
            # After dialnorm: compre (1), compr (8), chanmape (1), chanmap (16).
            fields = int.from_bytes(header[6:10], 'big')
            assert (fields >> 29) & 1, ('last dependent substream has no compre flag', location)
            assert (fields >> 21) & 0xff == 0, ('dependent compression is not unity gain', location)
            assert (fields >> 4) & 0x1ffff == 0x11a00, ('wrong dependent channel map', location)
        else:
            access_units += 1
        assert crc16(data[offset + 2:offset + size]) == 0, ('invalid frame CRC', location)
        expected_stream_type = 1 - stream_type if channels == 8 else 0
        offset += size
    assert access_units and expected_stream_type == 0, ('incomplete stream', path)
    kbps = len(data) * 8 * rate / (access_units * 1536 * 1000)
    print(f'{path.name}: {access_units} access units, {kbps:.1f} kbps including dependent frames', flush=True)

failures = []
for channels, layout in [(8, '7.1'), (6, '5.1')]:
    previous_failures = len(failures)
    src = out / f'input-{channels}.wav'
    pcm = array.array('h')
    for t in range(rate * 3):
        for c in range(channels):
            pcm.append(round(8000 * math.sin(2 * math.pi * freqs[c] * t / rate)))
    with wave.open(str(src), 'wb') as w:
        w.setnchannels(channels); w.setsampwidth(2); w.setframerate(rate); w.writeframes(pcm.tobytes())
    encoded = out / f'encoded-{channels}.eac3'
    decoded = out / f'decoded-{channels}.wav'
    run('-i', src, '-channel_layout', layout, '-c:a', 'eac3', '-b:a', '1024k' if channels == 8 else '640k', encoded)
    verify_syncframes(encoded, channels)
    # No -ac or layout forcing on decode: the stream must actually describe all channels.
    run('-i', encoded, '-c:a', 'pcm_s16le', decoded)
    with wave.open(str(decoded), 'rb') as w:
        assert w.getnchannels() == channels, (channels, w.getnchannels())
        assert w.getframerate() == rate
        data = array.array('h', w.readframes(w.getnframes()))
    n = min(rate, len(data) // channels - rate // 2)
    assert n > rate // 2, 'truncated decode'
    for c in range(channels):
        signal = [data[(t + rate // 2) * channels + c] for t in range(n)]
        energies = []
        for f in freqs[:channels]:
            re = sum(v * math.cos(2 * math.pi * f * t / rate) for t, v in enumerate(signal))
            im = sum(v * math.sin(2 * math.pi * f * t / rate) for t, v in enumerate(signal))
            energies.append((re * re + im * im) ** 0.5 / n)
        peak = max(range(channels), key=energies.__getitem__)
        if peak != c or not 3000 <= energies[c] <= 5000:
            failures.append((layout, 'missing or incorrect channel', c, peak, energies))
        leakage = max(v for k, v in enumerate(energies) if k != c)
        if energies[c] <= 5 * max(leakage, 1):
            failures.append((layout, 'excess crosstalk', c, energies))
        print(f'{layout} channel {c}: {freqs[c]} Hz expected; amplitude={energies[c]:.1f}; leakage={leakage:.1f}', flush=True)
    print(f'{layout}: {len(failures) - previous_failures} validation failures', flush=True)

for failure in failures:
    print('FAIL:', failure, flush=True)
assert not failures, f'{len(failures)} channel validation failures'
