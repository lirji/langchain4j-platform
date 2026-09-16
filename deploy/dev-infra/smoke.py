#!/usr/bin/env python3
"""用独立租户验证真实 MySQL/S3/Redis/ES/Qdrant；不调用外部模型，不删除既有数据。"""
import base64, hashlib, hmac, json, os, pathlib, secrets, socket, re, subprocess, sys, time, urllib.request
import manage as m

def main():
    urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
    host_mode='--host' in sys.argv
    # 先验证检索基础设施就绪，避免初始化期间制造可重试失败任务。
    print('Waiting for Elasticsearch readiness (up to 10 minutes)',flush=True)
    deadline=time.monotonic()+600
    while time.monotonic()<deadline:
        try:
            with urllib.request.urlopen('http://127.0.0.1:49200/_cluster/health?timeout=5s',timeout=45) as response:
                if json.load(response).get('status') in {'green','yellow'}:break
        except Exception:pass
        time.sleep(5)
    else:raise RuntimeError('Elasticsearch did not become ready within 10 minutes')
    print('Infrastructure ready; initializing isolated smoke database',flush=True)
    model=json.loads(m.run(['bash',str(m.DEPLOY/'dev-infra/compose.sh'),'config','--format','json']))
    env={str(k):str(v) for k,v in model['services']['knowledge-service']['environment'].items()}
    tenant='infra-smoke-'+secrets.token_hex(6)
    env.update(RAG_RUNTIME_ROLE='combined',RAG_PRODUCTION='true',RAG_LEGACY_WRITE_ENABLED='false',
        RAG_EMBEDDING_PROVIDER='hash',RAG_VECTOR_STORE_BASE_COLLECTION='lc4j_smoke_hash',
        RAG_GRAPH_ENABLED='false',RAG_RERANK_ENABLED='false',RAG_QUERY_EXPANSION_ENABLED='false',
        RAG_CONTEXTUAL_ENABLED='false',RAG_MULTIMODAL_ENABLED='false',RAG_PUBLIC_ENABLED='false',
        RAG_ES_INDEX_NAME='lc4j_smoke',RAG_QUERY_MIN_SCORE='0',MANAGEMENT_TRACING_ENABLED='false',
        JAVA_TOOL_OPTIONS='-Xms64m -Xmx384m -XX:MaxMetaspaceSize=192m',PLATFORM_EVENTBUS_TYPE='in-memory',APP_RAG_INGESTION_ASYNC_TASK_ENABLED='false')
    # 独立测试库避免 worker 认领迁移来的业务任务。
    database=os.environ.get('LC4J_SMOKE_DATABASE','lc4j_ingestion_smoke_'+secrets.token_hex(4))
    if not re.fullmatch(r'lc4j_ingestion_smoke_[0-9a-f]{8}',database):raise ValueError('Only isolated smoke databases are allowed')
    m.private(m.STATE/'smoke-run.json',json.dumps({'database':database,'tenant':tenant}))
    m.mysql(f"CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4; GRANT SELECT, INSERT, UPDATE, DELETE ON `{database}`.* TO 'lc4j_ingest_app'@'%'; GRANT ALL PRIVILEGES ON `{database}`.* TO 'lc4j_ingest_migrator'@'%';")
    env['RAG_INGESTION_DB_URL']=env['RAG_INGESTION_DB_URL'].replace('/lc4j_knowledge_ingestion?', '/'+database+'?')
    migration_env={str(k):str(v) for k,v in model['services']['migrate-knowledge-ingestion']['environment'].items()}
    migration_env['MIGRATION_DB_URL']=env['RAG_INGESTION_DB_URL']
    name=tenant
    local_log=None
    process=None
    if host_mode:
        env['RAG_INGESTION_DB_URL']=env['RAG_INGESTION_DB_URL'].replace('infra-mysql84:3306','127.0.0.1:43306')
        migration_env['MIGRATION_DB_URL']=env['RAG_INGESTION_DB_URL']
        m.run(['java','-Xmx128m','-jar',str(m.ROOT/'database-migrations/target/database-migrations-0.1.0-SNAPSHOT-exec.jar')],env={**os.environ,**migration_env})
        # actuator 已搬到独立 management 端口，健康探测和业务调用是两个端口
        with socket.socket() as reserved, socket.socket() as reserved_mgmt:
            reserved.bind(('127.0.0.1',0));port=reserved.getsockname()[1]
            reserved_mgmt.bind(('127.0.0.1',0));mgmt_port=reserved_mgmt.getsockname()[1]
        env.update(SERVER_PORT=str(port),MANAGEMENT_PORT=str(mgmt_port),
                   SPRING_DATA_REDIS_HOST='127.0.0.1',SPRING_DATA_REDIS_PORT='46379',
                   QDRANT_HOST='127.0.0.1',QDRANT_PORT='46334',RAG_ES_URIS='http://127.0.0.1:49200',
                   RAG_SOURCE_S3_ENDPOINT='http://127.0.0.1:49000')
        log_path=m.STATE/'smoke-container.log'
        m.private(log_path,b'')
        local_log=log_path.open('wb')
        process=subprocess.Popen(['java','-jar',str(m.ROOT/'knowledge-service/target/knowledge-service-0.1.0-SNAPSHOT.jar')],
            env={**os.environ,**env},stdout=local_log,stderr=subprocess.STDOUT)
    else:
        with socket.socket() as reserved, socket.socket() as reserved_mgmt:
            reserved.bind(('127.0.0.1',0));port=reserved.getsockname()[1]
            reserved_mgmt.bind(('127.0.0.1',0));mgmt_port=reserved_mgmt.getsockname()[1]
        m.run(['bash',str(m.DEPLOY/'dev-infra/compose.sh'),'run','--rm','--no-deps',
               '-e','MIGRATION_DB_URL='+env['RAG_INGESTION_DB_URL'],'migrate-knowledge-ingestion'])
        args=['docker','run','-d','--name',name,'--network','dev-infra','--memory','768m',
              '-p',f'127.0.0.1:{port}:8084','-p',f'127.0.0.1:{mgmt_port}:9084']
        for key in env:args+=['-e',key]
        args+=['langchain4j-platform-knowledge-service:latest']
        m.run(args,env={**os.environ,**env})
    def enc(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
    def mint_token():
        now=int(time.time());claims={'iss':'langchain4j-platform','aud':['platform-internal'],'sub':tenant,
            'uid':'infra-smoke','scopes':['ingest','query'],'token_use':'internal_access','jti':secrets.token_hex(16),'iat':now,'exp':now+120}
        raw=enc({'alg':'HS256','typ':'JWT','kid':'platform-internal-v1'})+'.'+enc(claims)
        token=raw+'.'+base64.urlsafe_b64encode(hmac.new(env['INTERNAL_JWT_SECRET'].encode(),raw.encode(),hashlib.sha256).digest()).decode().rstrip('=')
        return token
    def request(path,body=None,headers=None):
        req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=body,headers={'X-Internal-Token':mint_token(),**(headers or {})})
        with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)
    def probe(path):
        # management 端口不经过租户 filter，探测不带内部 token
        with urllib.request.urlopen(f'http://127.0.0.1:{mgmt_port}'+path,timeout=30) as response:return json.load(response)
    try:
        for attempt in range(600):
            try:
                probe('/actuator/health');break
            except Exception:
                if process is not None and process.poll() is not None:raise RuntimeError('Knowledge exited; inspect private smoke-container.log')
                time.sleep(1)
        else:raise RuntimeError('Knowledge smoke container did not become healthy')
        boundary='lc4j'+secrets.token_hex(8)
        def part(key,value,filename=None):
            disposition=f'Content-Disposition: form-data; name="{key}"'+(f'; filename="{filename}"' if filename else '')
            return f'--{boundary}\r\n{disposition}\r\n'+('Content-Type: text/plain\r\n' if filename else '')+f'\r\n{value}\r\n'
        body=(part('documentId','infra-smoke-document')+part('documentVersion','1')+
              part('file','dev-infra 验证文档：可靠入库通过 MySQL 保存任务，S3 保存原文，Redis 提交版本，Qdrant 和 Elasticsearch 提供检索。','infra-smoke.txt')+f'--{boundary}--\r\n').encode()
        headers={'Content-Type':'multipart/form-data; boundary='+boundary,'Idempotency-Key':tenant}
        job=request('/rag/ingestions',body,headers)
        print('Submitted smoke job '+job['jobId'],flush=True)
        again=request('/rag/ingestions',body,headers)
        assert job['jobId']==again['jobId'],'idempotency mismatch'
        for attempt in range(90):
            status=request('/rag/ingestions/'+job['jobId'])
            if status['status']=='READY':break
            time.sleep(1)
        else:raise RuntimeError('Ingestion did not succeed: '+json.dumps(status))
        reply=request('/rag/query',json.dumps({'query':'可靠入库 MySQL S3 Redis','topK':5}).encode(),{'Content-Type':'application/json'})
        # 提高向量阈值后验证 ES 来源，防止融合查询只命中向量而掩盖全文分支故障。
        es_reply=request('/rag/query',json.dumps({'query':'可靠入库 MySQL S3 Redis','topK':5,'minScore':1.0}).encode(),{'Content-Type':'application/json'})
        m.private(m.STATE/'smoke.json',json.dumps({'tenant':tenant,'database':database,'job':status,'query':reply,'esQuery':es_reply},ensure_ascii=False,indent=2))
        assert any(hit.get('source')=='es' for hit in es_reply.get('hits',[])), 'Elasticsearch source did not return smoke document'
        assert 'infra-smoke-document' in json.dumps(reply),'query did not retrieve smoke document'
        print('PASS: idempotent ingestion, all required sinks, committed version and query; tenant='+tenant)
    finally:
        if process is not None:
            process.terminate()
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            local_log.close()
        else:
            m.private(m.STATE/'smoke-container.log',m.run(['docker','logs',name]))
            m.run(['docker','stop',name])
if __name__=='__main__':main()
