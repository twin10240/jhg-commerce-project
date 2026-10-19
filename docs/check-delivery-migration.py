#!/usr/bin/env python3
"""Verify V16 on a disposable local PostgreSQL DB; never use a service DB."""
import os
from pathlib import Path
import re
import subprocess
import uuid

environment = dict(os.environ)
environment.setdefault('PGHOST', '127.0.0.1')
environment['PGCONNECT_TIMEOUT'] = '5'
if environment['PGHOST'] not in ('127.0.0.1', 'localhost', '::1'):
    raise SystemExit('Migration check requires local PostgreSQL')
database = 'oms_delivery_check_' + uuid.uuid4().hex
migrations = Path(__file__).resolve().parents[1] / 'src/main/resources/db/migration'
created = False


def sql(statement=None, file=None, check=True):
    args = ['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-At', '-d', database]
    args += ['-f', str(file)] if file else ['-c', statement]
    result = subprocess.run(args, env=environment, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(f'Migration SQL failed: {result.stderr}')
    return result


try:
    subprocess.run(['createdb', database], env=environment, check=True, capture_output=True)
    created = True
    files = sorted(migrations.glob('V*.sql'), key=lambda p: int(re.match(r'V(\d+)', p.name)[1]))
    for file in files:
        if int(re.match(r'V(\d+)', file.name)[1]) < 16:
            if file.name.startswith('V2__'):
                # V2 targets the pre-Flyway JPA baseline, whose product held this legacy FK.
                sql('ALTER TABLE product ADD COLUMN inventory_id BIGINT')
            sql(file=file)
    sql("""
        INSERT INTO delivery(delivery_id,status) VALUES (1,'READY'),(2,'SHIPPED'),(3,'DELIVERED');
        ALTER TABLE delivery ADD CONSTRAINT delivery_status_check CHECK (status IN ('READY','SHIPPED','DELIVERED'));
    """)
    sql(file=migrations / 'V16__add_delivery_failure_state.sql')
    states = sql("SELECT status || ':' || delivery_version FROM delivery ORDER BY delivery_id").stdout.splitlines()
    assert states == ['READY:0', 'SHIPPED:0', 'DELIVERED:0'], states
    sql("""
        INSERT INTO delivery(delivery_id,status,delivery_version,delivery_status_at,delivery_failed_at,delivery_failure_code)
        VALUES (10,'DELIVERY_FAILED',2,'2026-10-14T00:00:00.123456Z','2026-10-14T00:00:00.123456Z','ADDRESS_ISSUE');
    """)
    for statement, constraint in [
        ("UPDATE delivery SET delivery_version=-1 WHERE delivery_id=10", 'ck_delivery_version'),
        ("UPDATE delivery SET delivery_failure_code='UNKNOWN' WHERE delivery_id=10", 'ck_delivery_failure_code'),
    ]:
        rejected = sql(statement, check=False)
        assert rejected.returncode != 0 and constraint in rejected.stderr, 'Invalid delivery data was accepted'
    assert sql("SELECT status || ':' || delivery_version FROM delivery WHERE delivery_id=10").stdout.strip() == 'DELIVERY_FAILED:2'
    print('PASS: PostgreSQL V16 upgrade from reconstructed V15 legacy schema; existing states preserved, failure/version constraints verified')
finally:
    if created:
        subprocess.run(['dropdb', '--force', database], env=environment, check=True, capture_output=True)
        print('Cleaned up disposable migration database')
