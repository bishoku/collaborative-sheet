"""Set the project version from a validated release tag; never alter dependencies."""
import re
import sys
from pathlib import Path

tag = sys.argv[1]
if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?", tag):
    raise SystemExit("Expected a tag such as v0.1.0 or v0.1.0-rc.1")
version = tag[1:]
if 'SNAPSHOT' in version.upper():
    raise SystemExit("Release tags cannot contain SNAPSHOT")
pom = Path('pom.xml')
text, count = re.subn(r'(<artifactId>sheets-confluence-dc</artifactId>\s*<version>)[^<]+(</version>)',lambda m:m[1]+version+m[2],pom.read_text(),count=1)
if count != 1:
    raise SystemExit('Cannot locate project version')
pom.write_text(text)
print(version)
