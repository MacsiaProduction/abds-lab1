#!/usr/bin/env python3
"""Run after a fresh setup; writes only synthetic lab entities, never production."""
import base64
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ENV = dict(line.split('=', 1) for line in (ROOT / '.env.local').read_text().splitlines()
           if line and not line.startswith('#'))
assert re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]*', ENV['CLICKHOUSE_DATABASE'])
BASE = 'http://127.0.0.1:' + ENV['APP_PORT']
CHECKS = []


def api(method, path, payload=None, status=200):
    request = urllib.request.Request(BASE + path, method=method,
                                    data=None if payload is None else json.dumps(payload).encode(),
                                    headers={'Content-Type': 'application/json'})
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read()
        assert response.status == status, (method, path, response.status, body.decode())
        if body:
            assert response.headers.get_content_type() == 'application/json'
            return json.loads(body)
        return None


def sql(query, rows=True):
    parameters = urllib.parse.urlencode({'database': ENV['CLICKHOUSE_DATABASE'],
                                        'wait_end_of_query': '1',
                                        'output_format_json_quote_64bit_integers': '0'})
    url = 'http://127.0.0.1:' + ENV['CLICKHOUSE_HTTP_PORT'] + '/?' + parameters
    credentials = (ENV['CLICKHOUSE_USER'] + ':' + ENV['CLICKHOUSE_PASSWORD']).encode()
    request = urllib.request.Request(url, data=(query + (' FORMAT JSONEachRow' if rows else '')).encode(),
                                    headers={'Authorization': 'Basic ' + base64.b64encode(credentials).decode()})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = response.read().decode()
    return [json.loads(line) for line in body.splitlines() if line.strip()] if rows else body


def scalar(query):
    row = sql(query)[0]
    return next(iter(row.values()))


def check(name):
    CHECKS.append(name)
    print('PASS ' + name, flush=True)


def main():
    started = time.monotonic()
    version = scalar('SELECT version()')
    assert version.startswith('26.7.'), version
    for statement in (ROOT / 'sql/checks.sql').read_text().split(';'):
        if statement.strip():
            sql(statement, rows=False)
    check('fresh dataset counts, engines current views and historical prices')
    assert api('GET', '/ping') is None
    assert api('GET', '/users/1')['id'] == 1
    assert api('GET', '/realty/1')['id'] == 1
    assert api('GET', '/users/18446744073709551615', status=404)['message'] == 'Not found'
    check('ping, users, realty and maximum unsigned ID lookup')

    name = "O'Reilly; DROP DATABASE energosbyt; --"
    api('PATCH', '/users/1', {'fio': name}, status=204)
    for phone in ['+79111111111', '+79222222222']:
        api('PATCH', '/users/1', {'phone': phone}, status=204)
    user = api('GET', '/users/1')
    assert (user['fio'], user['phone'], user['phone_prev']) == (name, '+79222222222', '+79111111111')
    current = sql('SELECT fio, phone, phone_prev FROM dim_users FINAL WHERE id=1')
    assert current == [dict(fio=name, phone='+79222222222', phone_prev='+79111111111')]
    assert scalar('SELECT count() FROM dim_realty') == 1000
    check('SCD1, SCD3 and quote-containing input through real SQL transport')

    before = api('GET', '/tariffs?date=2025-10-31')
    after = api('GET', '/tariffs?date=2025-11-01')
    assert before['id'] == after['id'] == 1
    assert before['valid_from'] != after['valid_from']
    assert (before['price_day_kwph'], after['price_day_kwph']) == (500, 620)
    assert api('GET', '/tariffs?date=1900-01-01', status=404)['message'] == 'Not found'
    api('GET', '/tariffs', status=400)
    check('SCD2 exact boundary, missing and uncovered dates')

    payload = dict(user_id=1, gauge_id=1, tariff_id=1, reading_date='2026-10-01', day_kw=1250, night_kw=300)
    api('POST', '/realty/1/readings', payload, status=201)
    first_time = scalar("SELECT created_at FROM fact_meter_readings FINAL WHERE realty_id=1 AND reading_date='2026-10-01'")
    payload.update(gauge_id=2, day_kw=1251)
    api('POST', '/realty/1/readings', payload, status=201)
    result = api('GET', '/realty/1/readings?date_from=2026-10-01&date_to=2026-10-01')
    assert result['count'] == 1 and result['readings'][0]['day_kw'] == 1251 and result['readings'][0]['gauge_id'] == 2
    stored = sql("SELECT created_at, gauge_id, day_kw FROM fact_meter_readings FINAL WHERE realty_id=1 AND reading_date='2026-10-01'")
    assert len(stored) == 1 and stored[0]['created_at'] > first_time
    assert api('GET', '/realty/1/readings?date_from=1900-01-01&date_to=1900-12-31') == dict(readings=[], count=0)
    check('correction with changed gauge, strict timestamp and FINAL deduplication without OPTIMIZE')

    bills = api('GET', '/realty/1/invoices')
    assert bills['count'] == 36 and len(bills['invoices']) == 10
    assert [b['created_at'] for b in bills['invoices']] == sorted([b['created_at'] for b in bills['invoices']], reverse=True)
    assert api('GET', '/realty/1/invoices?limit=0') == dict(invoices=[], count=36)
    page = api('GET', '/realty/1/invoices?limit=1&offset=1')
    assert page['count'] == 36 and page['invoices'] == bills['invoices'][1:2]
    check('invoice default, zero limit, offset and total count')

    version_before = scalar('SELECT version FROM fact_gauge_bindings FINAL WHERE realty_id=1')
    for gauge in [2, 2, 1]:
        api('POST', '/realty/1/gauges', dict(gauge_id=gauge), status=201)
    active = sql('SELECT gauge_id, version, sign FROM fact_gauge_bindings FINAL WHERE realty_id=1')
    assert active == [dict(gauge_id=1, version=version_before + 3, sign=1)], active
    check('multiple and repeated gauge replacements collapse to one active row')

    for path in ['/realty/1/invoices?limit=-1', '/tariffs?date=2026-02-30',
                 '/realty/1/readings?date_from=2026-10-02&date_to=2026-10-01']:
        api('GET', path, status=400)
    for field in ['user_id', 'gauge_id', 'tariff_id']:
        bad = dict(payload, **{field: 999999999})
        api('POST', '/realty/1/readings', bad, status=404)
    api('POST', '/realty/1/readings', dict(payload, day_kw=-1), status=400)
    api('PATCH', '/users/1', {}, status=400)
    api('POST', '/realty/1/gauges', {}, status=400)
    check('real-backend 400 and missing-reference 404 paths')

    sql("INSERT INTO fact_invoice (realty_id,user_id,tariff_id,gauge_id,day_kw,night_kw,price,deadline,created_at) "
        "VALUES (999999999,1,1,1,10,1,9999990,'2026-11-10','2026-10-31 12:00:00')", rows=False)
    assert scalar('SELECT count() FROM fact_invoice WHERE realty_id=999999999') == 1
    sql('ALTER TABLE fact_invoice DELETE WHERE realty_id=999999999 SETTINGS mutations_sync=1', rows=False)
    assert scalar('SELECT count() FROM fact_invoice WHERE realty_id=999999999') == 0
    check('synchronous deletion of deliberately erroneous synthetic row')
    receipt = dict(clickhouse_version=version, checks=CHECKS, seconds=round(time.monotonic() - started, 3))
    (ROOT / 'build').mkdir(exist_ok=True)
    (ROOT / 'build/e2e-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
