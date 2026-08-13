"""Dumps the English side as blank translation tables, ready to fill in.

Writes loc/_keys.tsv, loc/_nodes.tsv and loc/_plurals.tsv with the English text in
both columns, so a table can be copied per locale and the second column replaced.
"""
import io
import os
import re

RES = 'app/src/main/res/values'
STRING = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.S)
ARRAY = re.compile(r'<string-array name="([^"]+)"[^>]*>(.*?)</string-array>', re.S)
PLURAL = re.compile(r'<plurals name="([^"]+)"[^>]*>(.*?)</plurals>', re.S)
ITEM = re.compile(r'<item[^>]*>(.*?)</item>', re.S)

keys, nodes, plurals = [], set(), []
for name in sorted(os.listdir(RES)):
    if not name.endswith('.xml'):
        continue
    src = io.open(os.path.join(RES, name), encoding='utf-8').read()
    for key, attrs, value in STRING.findall(src):
        if 'translatable="false"' in attrs:
            continue
        if name == 'strings_nodes.xml':
            nodes.add(value)
        else:
            keys.append((key, value))
    for key, body in ARRAY.findall(src):
        for i, item in enumerate(ITEM.findall(body)):
            keys.append(('%s:%d' % (key, i), item))
    for key, body in PLURAL.findall(src):
        for q, text in re.findall(r'<item quantity="([^"]+)"[^>]*>(.*?)</item>', body, re.S):
            plurals.append(('%s:%s' % (key, q), text))

for path, rows in (('loc/_keys.tsv', keys),
                   ('loc/_nodes.tsv', sorted((v, v) for v in nodes)),
                   ('loc/_plurals.tsv', plurals)):
    io.open(path, 'w', encoding='utf-8', newline='').write(
        '\n'.join('%s\t%s' % (a, b) for a, b in rows) + '\n')
    print('%-18s %d' % (path, len(rows)))
