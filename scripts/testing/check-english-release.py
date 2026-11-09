"""Check tracked implementation text and compiled JAR constants for CJK ideographs."""
from pathlib import Path
import argparse
import json
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CJK = re.compile('[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\U00020000-\U0002fa1f]')
CODE_EXTENSIONS = {'.java', '.gradle', '.properties', '.json', '.bat', '.sh', '.ps1', '.py'}


def class_strings(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid class header')
    count = struct.unpack_from('>H', data, 8)[0]
    offset, index = 10, 1
    sizes = {3:4, 4:4, 5:8, 6:8, 7:2, 8:2, 9:4, 10:4, 11:4, 12:4,
             15:3, 16:2, 17:4, 18:4, 19:2, 20:2}
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, offset)[0]
            offset += 2
            value = data[offset:offset+length].replace(b'\xc0\x80', b'\x00')
            text = value.decode('utf-8', errors='surrogatepass')
            # Java modified UTF-8 encodes supplementary characters as surrogate pairs.
            yield text.encode('utf-16-le', errors='surrogatepass').decode('utf-16-le', errors='surrogatepass')
            offset += length
        else:
            offset += sizes[tag]
            if tag in (5, 6): index += 1
        index += 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path)
    args = parser.parse_args()
    problems = []
    files = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT).decode().split('\0')
    checked = 0
    for name in set(files):
        if not name: continue
        path = ROOT / name
        if not path.is_file() or (path.suffix not in CODE_EXTENSIONS and path.name not in {'AGENTS.md', '.gitignore'}): continue
        checked += 1
        if CJK.search(name): problems.append(name + ': filename')
        text = path.read_text(encoding='utf-8-sig')
        for line, value in enumerate(text.splitlines(), 1):
            if CJK.search(value): problems.append(f'{name}:{line}')
    class_count = 0
    if args.jar:
        with zipfile.ZipFile(args.jar) as jar:
            if jar.testzip() is not None: raise ValueError('Corrupt JAR entry')
            names = jar.namelist()
            if len(names) != len(set(names)): raise ValueError('Duplicate JAR entries')
            for info in jar.infolist():
                if CJK.search(info.filename): problems.append(info.filename + ': filename')
                data = jar.read(info)
                if info.filename.endswith('.class'):
                    class_count += 1
                    if any(CJK.search(s) for s in class_strings(data)): problems.append(info.filename + ': constant pool')
                elif Path(info.filename).suffix in {'.json', '.properties', '.txt', '.md', '.toml'}:
                    if CJK.search(data.decode('utf-8-sig')): problems.append(info.filename + ': text resource')
                    if info.filename.endswith('.json'):
                        decoded = json.loads(data)
                        if CJK.search(json.dumps(decoded, ensure_ascii=False)): problems.append(info.filename + ': decoded JSON')
    if problems:
        raise SystemExit('CJK text remains:\n' + '\n'.join(problems))
    print(f'English release check passed: {checked} implementation/build files; {class_count} compiled classes; zero CJK ideographs')


if __name__ == '__main__':
    main()
