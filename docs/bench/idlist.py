#!/usr/bin/env python3
"""Capture the FULL ordered result of a Teedy search, page by page.

The result-set + ordering equality proof for a search-path change: run this against the
instance before and after, then `diff` the two files. Any reordering or any added/removed
document shows up as a diff line.

Usage: idlist.py --base http://localhost:18093 --search der [--sort 3] [--asc false]
"""
import argparse, sys
import requests

ap = argparse.ArgumentParser()
ap.add_argument('--base', required=True)
ap.add_argument('--search', required=True)
ap.add_argument('--sort', default='3')
ap.add_argument('--asc', default='false')
ap.add_argument('--page', type=int, default=100)
a = ap.parse_args()

login = requests.post(a.base + '/api/user/login',
                      data={'username': 'admin', 'password': 'admin', 'remember': 'true'}, timeout=30)
login.raise_for_status()
cookies = {'auth_token': login.cookies['auth_token']}

offset, total, ids = 0, None, []
while True:
    r = requests.get(a.base + '/api/document/list', cookies=cookies, timeout=300,
                     params={'search': a.search, 'limit': a.page, 'offset': offset,
                             'sort_column': a.sort, 'asc': a.asc})
    r.raise_for_status()
    body = r.json()
    total = body['total']
    batch = body['documents']
    if not batch:
        break
    for d in batch:
        ids.append('%s\t%s' % (d['id'], d['title']))
    offset += len(batch)
    if offset >= total:
        break

print('# total=%d collected=%d search=%s sort=%s asc=%s' % (total, len(ids), a.search, a.sort, a.asc))
for line in ids:
    print(line)
sys.exit(0)
