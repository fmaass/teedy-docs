#!/usr/bin/env python3
"""Seed one Teedy instance with the #290 benchmark corpus over the REST API.

Usage: seed.py --base http://localhost:18091 [--docs 3000] [--workers 8]
Auth is the auth_token cookie from POST /api/user/login (admin/admin).
"""
import argparse, concurrent.futures as cf, sys, threading, time
import requests
sys.path.insert(0, __file__.rsplit('/', 1)[0])
import corpus

ap = argparse.ArgumentParser()
ap.add_argument('--base', required=True)
ap.add_argument('--docs', type=int, default=3000)
ap.add_argument('--workers', type=int, default=8)
a = ap.parse_args()

login = requests.post(a.base + '/api/user/login',
                      data={'username': 'admin', 'password': 'admin', 'remember': 'true'},
                      timeout=30)
login.raise_for_status()
COOKIES = {'auth_token': login.cookies['auth_token']}

me = requests.get(a.base + '/api/user', cookies=COOKIES, timeout=30).json()
print('quota=%s current=%s' % (me.get('storage_quota'), me.get('storage_current')), flush=True)
app = requests.get(a.base + '/api/app', cookies=COOKIES, timeout=30).json()
print('app=%s' % {k: v for k, v in app.items() if k in
                  ('current_version', 'min_version', 'commit_id', 'total_memory')}, flush=True)

PLACEMENTS = corpus.placements(a.docs)
lock = threading.Lock()
done = [0]
errors = []
local = threading.local()


def sess():
    if not hasattr(local, 's'):
        local.s = requests.Session()
        local.s.cookies.update(COOKIES)
    return local.s


def one(i):
    s = sess()
    text = corpus.page(i, PLACEMENTS.get(i, []))
    r = s.put(a.base + '/api/document',
              data={'title': corpus.title(i), 'language': 'deu',
                    'description': 'Benchmarkbeleg %d' % i}, timeout=60)
    if r.status_code != 200:
        return 'doc %d -> %s %s' % (i, r.status_code, r.text[:160])
    doc_id = r.json()['id']
    r = s.put(a.base + '/api/file', data={'id': doc_id},
              files={'file': ('beleg-%04d.txt' % i, text.encode('utf-8'), 'text/plain')},
              timeout=120)
    if r.status_code != 200:
        return 'file %d -> %s %s' % (i, r.status_code, r.text[:160])
    with lock:
        done[0] += 1
        if done[0] % 250 == 0:
            print('  seeded %d/%d  %.0fs' % (done[0], a.docs, time.time() - T0), flush=True)
    return None


T0 = time.time()
with cf.ThreadPoolExecutor(max_workers=a.workers) as ex:
    for err in ex.map(one, range(a.docs)):
        if err:
            errors.append(err)
            if len(errors) > 5:
                break
print('seeded=%d errors=%d elapsed=%.0fs' % (done[0], len(errors), time.time() - T0), flush=True)
for e in errors[:6]:
    print('ERR', e, flush=True)
sys.exit(1 if errors else 0)
