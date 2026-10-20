"""Fail before publishing if the installable JAR metadata or license is incorrect."""
import hashlib
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

version = sys.argv[1]
jar = Path('target') / ('sheets-confluence-dc-' + version + '.jar')
with zipfile.ZipFile(jar) as archive:
    descriptor = ET.fromstring(archive.read('atlassian-plugin.xml'))
    assert descriptor.findtext('plugin-info/version') == version, 'Incorrect plugin version'
    assert descriptor.attrib['key'] == 'com.bishokudev.confluence.sheets-confluence-dc'
    assert b'Apache License' in archive.read('META-INF/LICENSE')
    assert b'Bishoku' in archive.read('META-INF/NOTICE')
    assert not any('FaultProbe' in name for name in archive.namelist()), 'Test hook bundled'
    assert 'js/sheets.js' in archive.namelist()
jar.with_suffix('.jar.sha256').write_text(hashlib.sha256(jar.read_bytes()).hexdigest() + '  ' + jar.name + '\n')
print('Verified:', jar)
