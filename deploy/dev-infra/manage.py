#!/usr/bin/env python3
"""dev-infra 接入工具。凭据只写 0600 本机文件；备份/导入不删除旧卷、不覆盖非空目标库。"""
import argparse, base64, datetime, hashlib, json, os, pathlib, re, secrets, socket, subprocess, sys, time
ROOT = pathlib.Path(__file__).resolve().parents[2]
DEPLOY = ROOT / 'deploy'
INFRA = pathlib.Path(os.environ.get('DEV_INFRA_HOME', ROOT.parent / 'dev-infra'))
STATE = DEPLOY / '.dev-infra-state'
ENV = DEPLOY / '.dev-infra.env'
DATABASES = {'auth':'auth', 'async_task':'async', 'flowable':'workflow', 'knowledge_graph':'graph',
             'knowledge_ingestion':'ingest', 'order_service':'order', 'channel':'channel', 'nl2sql_demo':'analytics'}
OLD = 'langchain4j-platform-'
MYSQL = 'dev-infra-mysql84-1'
PG = 'dev-infra-postgres16-1'
TOPICS = ['platform.workflow.terminal','platform.asynctask.lifecycle','platform.audit.events',
          'platform.metering.usage','platform.channel.events']

def run(args, data=None, env=None):
    p = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    if p.returncode:
        # 命令参数/错误文本可能含凭据，不直接回显。
        private(STATE / "last-error.txt", p.stderr)
        raise RuntimeError(f'{args[0]} command failed (exit {p.returncode}); details saved in private state directory')
    return p.stdout

def file_hash(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()

def private(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'wb') as f: f.write(data if isinstance(data, bytes) else data.encode())

def published_port(name, port):
    obj=json.loads(run(['docker','inspect',name]))[0]
    return int(obj['NetworkSettings']['Ports'][str(port)+'/tcp'][0]['HostPort'])

def container_env(name):
    obj = json.loads(run(['docker','inspect',name]))[0]
    return dict(v.split('=',1) for v in obj['Config']['Env'] if '=' in v)

def credentials():
    if not ENV.exists():
        vals = {}
        for db, short in DATABASES.items():
            for role in ['APP','MIGRATOR']: vals[f'LC4J_{short.upper()}_{role}_PASSWORD'] = secrets.token_hex(24)
        for key in ['PG','REDIS','S3']: vals[f'LC4J_{key}_PASSWORD'] = secrets.token_hex(24)
        private(ENV, ''.join(f'{k}={v}\n' for k,v in vals.items()))
    return dict(line.split('=',1) for line in ENV.read_text().splitlines() if line and not line.startswith('#'))

def mysql(sql, target=MYSQL, database=None):
    args=['docker','exec','-i',target,'sh','-c',
          'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --skip-column-names -uroot "$@"','sh']
    if database: args.append(database)
    return run(args, sql.encode())

def postgres(sql):
    return run(['docker','exec','-i',PG,'sh','-c','exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -At'],sql.encode())

class Redis:
    def __init__(self, port, password=None, user=None):
        self.sock=socket.create_connection(('127.0.0.1',port), timeout=30)
        self.file=self.sock.makefile('rb')
        if password: self.cmd('AUTH', *([user,password] if user else [password]))
    def cmd(self,*args):
        chunks=[a if isinstance(a,bytes) else str(a).encode() for a in args]
        self.sock.sendall(b'*'+str(len(chunks)).encode()+b'\r\n'+b''.join(b'$'+str(len(a)).encode()+b'\r\n'+a+b'\r\n' for a in chunks))
        return self.read()
    def read(self):
        line=self.file.readline(); kind=line[:1]; value=line[1:-2]
        if kind==b'-': raise RuntimeError('Redis operation rejected: '+value.decode().split(' ')[0])
        if kind==b'+': return value
        if kind==b':': return int(value)
        if kind==b'$':
            size=int(value)
            if size<0:return None
            data=self.file.read(size); self.file.read(2); return data
        if kind==b'*': return [self.read() for _ in range(int(value))]
        raise RuntimeError('Redis disconnected')
    def keys(self,pattern='*'):
        cursor=0
        while True:
            cursor,keys=self.cmd('SCAN',cursor,'MATCH',pattern,'COUNT',500)
            yield from keys
            if cursor==b'0':break

def provision():
    vals=credentials()
    for db,short in DATABASES.items():
        target='lc4j_'+db
        # 独立命名空间；重跑只保证存在，不 ALTER 已有账号密码。
        sql=f"CREATE DATABASE IF NOT EXISTS `{target}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
        for role in ['APP','MIGRATOR']:
            user=f'lc4j_{short}_{role.lower()}'
            pwd=vals[f'LC4J_{short.upper()}_{role}_PASSWORD']
            permissions='ALL PRIVILEGES' if role=='MIGRATOR' else ('SELECT' if db=='nl2sql_demo' else 'SELECT, INSERT, UPDATE, DELETE')
            sql+=f"CREATE USER IF NOT EXISTS '{user}'@'%' IDENTIFIED BY '{pwd}';\nGRANT {permissions} ON `{target}`.* TO '{user}'@'%';\n"
        mysql(sql)
    if not postgres("SELECT 1 FROM pg_roles WHERE rolname='lc4j_litellm';").strip():
        postgres(f"CREATE ROLE lc4j_litellm LOGIN PASSWORD '{vals['LC4J_PG_PASSWORD']}';")
    if not postgres("SELECT 1 FROM pg_database WHERE datname='lc4j_litellm';").strip():
        postgres('CREATE DATABASE lc4j_litellm OWNER lc4j_litellm;')
    admin=container_env('dev-infra-redis7-1')['REDIS7_PASSWORD']
    redis=Redis(46379, admin)
    redis.cmd('ACL','SETUSER','lc4j','reset','on','>'+vals['LC4J_REDIS_PASSWORD'],'~lc4j:*','&lc4j:*',
              '+@read','+@write','+@connection','+@scripting','+@transaction','+info','-flushall','-flushdb')
    # 共享实例禁止动态修改 aclfile；dev-infra 的项目 ACL sidecar 在重启后恢复账号，避免重启共享 Redis。
    for topic in TOPICS:
        for suffix in ['', '.DLT']:
            run(['docker','exec','dev-infra-kafka38-1','/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','infra-kafka38:9092',
                 '--create','--if-not-exists','--topic','lc4j.'+topic+suffix,'--partitions','1','--replication-factor','1'])
    print('Provisioned isolated MySQL/PostgreSQL/Redis/Kafka resources; credentials remain local.')

def provision_s3():
    vals=credentials(); admin=container_env('dev-infra-minio-1')
    env={**os.environ, 'MINIO_ROOT_USER':admin['MINIO_ROOT_USER'], 'MINIO_ROOT_PASSWORD':admin['MINIO_ROOT_PASSWORD'],
         'LC4J_S3_PASSWORD':vals['LC4J_S3_PASSWORD']}
    script = """set -eu
mc alias set infra http://infra-minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing infra/lc4j-knowledge-sources >/dev/null
if ! mc admin user info infra lc4j-knowledge >/dev/null 2>&1; then
  mc admin user add infra lc4j-knowledge "$LC4J_S3_PASSWORD" >/dev/null
fi
mc admin policy create infra lc4j-knowledge /policy.json >/dev/null
mc admin policy attach infra lc4j-knowledge --user lc4j-knowledge >/dev/null
"""
    run(['docker','run','--rm','--network','dev-infra','--entrypoint','/bin/sh',
         '-e','MINIO_ROOT_USER','-e','MINIO_ROOT_PASSWORD','-e','LC4J_S3_PASSWORD',
         '-v',str(DEPLOY/'dev-infra/minio-policy.json')+':/policy.json:ro',
         'minio/mc:RELEASE.2025-08-13T08-35-41Z','-c',script],env=env)
    print('Provisioned isolated S3 bucket and project policy.')

def backup():
    # 旧应用须全部停止，备份期间不得并行写旧库。
    running=run(['docker','ps','--filter','label=com.docker.compose.project=langchain4j-platform','--format','{{.Names}}']).decode().splitlines()
    allowed={OLD+n+'-1' for n in ['mysql','redis','litellm-postgres','kafka','qdrant','elasticsearch','kibana','jaeger']}
    if any(n not in allowed for n in running): raise RuntimeError('Stop platform applications before taking a consistent backup')
    if any(OLD+n+'-1' in running for n in ['qdrant','elasticsearch']):
        raise RuntimeError('Stop old Qdrant/Elasticsearch before filesystem backup')
    STATE.mkdir(parents=True,exist_ok=True); os.chmod(STATE,0o700)
    stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S'); folder=STATE/stamp;folder.mkdir(mode=0o700)
    for name in ['mysql','redis','litellm-postgres']:
        run(['docker','start',OLD+name+'-1'])
    for _ in range(30):
        try: mysql('SELECT 1;', OLD+'mysql-1');break
        except RuntimeError: time.sleep(1)
    manifest={'created':stamp,'databases':{},'volumes':[]}
    for db in DATABASES:
        data=run(['docker','exec',OLD+'mysql-1','sh','-c',
                  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-add-drop-table "$1"','sh',db])
        if len(data)<100:raise RuntimeError('Empty MySQL backup')
        private(folder/(db+'.sql'),data)
        manifest['databases'][db]=counts(OLD+'mysql-1',db)
    pg=run(['docker','exec',OLD+'litellm-postgres-1','pg_dump','-U','litellm','-d','litellm','--no-owner','--no-acl'])
    private(folder/'litellm.sql',pg)
    manifest['postgresCounts']=pg_counts(OLD+'litellm-postgres-1','litellm')
    redis=Redis(published_port(OLD+'redis-1',6379))
    records=[]
    for db in range(16):
        redis.cmd('SELECT',db)
        for key in redis.keys():
            payload=redis.cmd('DUMP',key);ttl=redis.cmd('PTTL',key)
            if payload is not None:
                records.append({'db':db,'key':base64.b64encode(key).decode(),'dump':base64.b64encode(payload).decode(),
                                'expires':int(time.time()*1000)+ttl if ttl>0 else None})
    private(folder/'redis.json',json.dumps(records))
    for suffix in ['qdrant-data','es-data','knowledge-source-data']:
        volume='langchain4j-platform_'+suffix
        target=folder/(suffix+'.tar')
        run(['docker','run','--rm','--network','none','--mount',f'type=volume,source={volume},target=/source,readonly',
             '--mount',f'type=bind,source={folder},target=/backup','--entrypoint','tar','postgres:16','--sparse','-cf','/backup/'+target.name,'-C','/source','.'])
        os.chmod(target,0o600);manifest['volumes'].append(volume)
    manifest['redisKeys']=len(records)
    manifest['checksums']={p.name:file_hash(p) for p in folder.iterdir() if p.is_file()}
    private(folder/'manifest.json',json.dumps(manifest,indent=2))
    private(STATE/'latest',str(folder))
    print('Backup complete:',folder,'; Redis records:',len(records))

def counts(container,database):
    tables=mysql(f"SELECT table_name FROM information_schema.tables WHERE table_schema='{database}' AND table_type='BASE TABLE';",container).decode().splitlines()
    return {table:int(mysql(f'SELECT COUNT(*) FROM `{table}`;',container,database).strip()) for table in tables}

def pg_counts(container, database):
    """逐表核对 PostgreSQL，避免将非空目标误判为已完成迁移。"""
    def query(sql):
        return run(['docker','exec','-i',container,'sh','-c',
                    'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" -At','sh',database],sql.encode()).decode().splitlines()
    tables=query("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;")
    return {table:int(query('SELECT count(*) FROM public."'+table.replace('"','""')+'";')[0]) for table in tables}

def migrate():
    folder=pathlib.Path((STATE/'latest').read_text());manifest=json.loads((folder/'manifest.json').read_text())
    for name,digest in manifest['checksums'].items():
        if file_hash(folder/name)!=digest:raise RuntimeError('Backup checksum mismatch')
    for db in DATABASES:
        target='lc4j_'+db
        present=counts(MYSQL,target)
        if present:
            if present!=manifest['databases'][db]:raise RuntimeError('Nonempty target differs; refusing overwrite: '+target)
        else:mysql((folder/(db+'.sql')).read_text(),MYSQL,target)
        if counts(MYSQL,target)!=manifest['databases'][db]:raise RuntimeError('Row count mismatch: '+target)
    n=run(['docker','exec',PG,'sh','-c','exec psql -U "$POSTGRES_USER" -d lc4j_litellm -Atc "SELECT count(*) FROM pg_tables WHERE schemaname=\'public\'"']).strip()
    if n==b'0':
        run(['docker','exec','-i',PG,'sh','-c','exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d lc4j_litellm'],
            b'SET ROLE lc4j_litellm;\n'+(folder/'litellm.sql').read_bytes())
    expected=manifest.get('postgresCounts')
    if expected is None: raise RuntimeError('Backup lacks PostgreSQL counts; take a new backup')
    if pg_counts(PG,'lc4j_litellm') != expected: raise RuntimeError('PostgreSQL target row counts differ; refusing further migration')
    vals=credentials();target=Redis(46379, vals['LC4J_REDIS_PASSWORD'],'lc4j')
    restored=0
    for record in json.loads((folder/'redis.json').read_text()):
        target.cmd('SELECT',record['db']);key=b'lc4j:'+base64.b64decode(record['key'])
        ttl=0 if record['expires'] is None else record['expires']-int(time.time()*1000)
        if ttl<0:continue
        payload=base64.b64decode(record['dump']);existing=target.cmd('DUMP',key)
        if existing is None:target.cmd('RESTORE',key,ttl,payload)
        elif existing!=payload:raise RuntimeError('Redis target key differs; refusing overwrite')
        restored+=1
    for suffix in ['qdrant-data','es-data']:
        volume='dev-infra-lc4j-'+suffix
        marker=STATE/(volume+'.clone.json')
        if volume not in run(['docker','volume','ls','--format','{{.Name}}']).decode().splitlines():
            run(['docker','volume','create',volume])
            run(['docker','run','--rm','--network','none','-v',volume+':/target','-v',str(folder)+':/backup:ro',
                 '--entrypoint','tar','postgres:16','-xf','/backup/'+suffix+'.tar','-C','/target'])
            private(marker,json.dumps({'sha256':manifest['checksums'][suffix+'.tar']}))
        elif not marker.exists() or json.loads(marker.read_text())['sha256']!=manifest['checksums'][suffix+'.tar']:
            raise RuntimeError('Existing clone has no matching completion marker: '+volume)
    private(STATE/'migration.json',json.dumps({'backup':str(folder),'mysqlCountsMatch':True,'postgresCountsMatch':True,'redisRestored':restored},indent=2))
    print('MySQL row counts match; Redis restored:',restored,'; dedicated vector/search volumes cloned.')

def migrate_sources():
    """仅从备份克隆启动临时 MinIO；通过 S3 导入，绝不把内部磁盘格式塞进共享桶。"""
    folder=pathlib.Path((STATE/'latest').read_text())
    manifest=json.loads((folder/'manifest.json').read_text())
    archive=folder/'knowledge-source-data.tar'
    if file_hash(archive)!=manifest['checksums'][archive.name]: raise RuntimeError('Source backup checksum mismatch')
    volume='lc4j-source-import-'+secrets.token_hex(4)
    name=volume
    run(['docker','volume','create',volume])
    run(['docker','run','--rm','--network','none','-v',volume+':/target','-v',str(folder)+':/backup:ro',
         '--entrypoint','tar','postgres:16','-xf','/backup/knowledge-source-data.tar','-C','/target'])
    vals=credentials()
    env={**os.environ,'MINIO_ROOT_USER':'lc4j-import','MINIO_ROOT_PASSWORD':secrets.token_hex(24),
         'LC4J_S3_PASSWORD':vals['LC4J_S3_PASSWORD']}
    try:
        run(['docker','run','-d','--name',name,'--network','dev-infra','-e','MINIO_ROOT_USER','-e','MINIO_ROOT_PASSWORD',
             '-v',volume+':/data','minio/minio:RELEASE.2025-09-07T16-13-09Z','server','/data'],env=env)
        script = """set -eu
for attempt in $(seq 1 30); do
  mc alias set old http://"$IMPORT_HOST":9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1 && break
  sleep 1
done
mc alias set target http://infra-minio:9000 lc4j-knowledge "$LC4J_S3_PASSWORD" >/dev/null
for attempt in $(seq 1 30); do mc ready old >/dev/null 2>&1 && break; sleep 1; done
mc mirror --preserve old/knowledge-sources target/lc4j-knowledge-sources >/dev/null
mc diff --json old/knowledge-sources target/lc4j-knowledge-sources
"""
        env['IMPORT_HOST']=name
        result=run(['docker','run','--rm','--network','dev-infra','--entrypoint','/bin/sh',
             '-e','MINIO_ROOT_USER','-e','MINIO_ROOT_PASSWORD','-e','LC4J_S3_PASSWORD','-e','IMPORT_HOST',
             'minio/mc:RELEASE.2025-08-13T08-35-41Z','-c',script],env=env)
        if result.strip():
            private(STATE/'source-diff.json',result)
            raise RuntimeError('S3 objects differ; inspect private source-diff report')
        mysql("UPDATE KNOWLEDGE_INGESTION_JOB SET SOURCE_BUCKET='lc4j-knowledge-sources' WHERE SOURCE_BUCKET='knowledge-sources';",MYSQL,'lc4j_knowledge_ingestion')
        private(STATE/'sources.json',json.dumps({'backup':str(folder),'objectsMatch':True,'retainedClone':volume}))
        print('S3 objects copied and compared; migrated job bucket references updated.')
    finally:
        run(['docker','stop',name])
    # 临时克隆卷与容器保留便于审计；不触碰原始卷。

def main():
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['provision','provision_s3','backup','migrate','migrate_sources']);args=parser.parse_args()
    globals()[args.command]()
if __name__=='__main__':
    try:main()
    except Exception as e: print(type(e).__name__+': '+str(e),file=sys.stderr);sys.exit(1)
