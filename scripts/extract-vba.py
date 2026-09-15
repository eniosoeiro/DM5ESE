"""Extract VBA for static inspection. Does not open Excel or execute macros."""
import hashlib
import json
import sys
from pathlib import Path
from oletools.olevba import VBA_Parser

source = Path(sys.argv[1]).resolve(strict=True)
destination = Path(sys.argv[2]).resolve()
destination.mkdir(parents=True, exist_ok=True)
parser = VBA_Parser(str(source))
modules = []
try:
    for _, stream, filename, code in parser.extract_macros():
        name = Path(filename).name
        (destination / name).write_text(code.replace("\r\n", "\n"), encoding="utf-8")
        modules.append({"file": name, "stream": stream, "lines": len(code.splitlines())})
finally:
    parser.close()
report = {"source": str(source), "sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "modules": modules}
(destination / "source.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report, indent=2))
