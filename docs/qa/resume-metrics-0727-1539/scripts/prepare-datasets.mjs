import fs from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '../../../..');
const qaDir = path.resolve(import.meta.dirname, '..');
const datasetsDir = path.join(qaDir, 'datasets');
const liteLlmUrl = process.env.LITELLM_URL || 'http://localhost:4000/v1/chat/completions';
const liteLlmKey = process.env.LITELLM_KEY || 'sk-litellm-master';

const corpus = [
  'docs/对话与检索/rag-guide.md',
  'docs/对话与检索/向量检索-ann与rrf.md',
  'docs/对话与检索/es-hybrid-rerank.md',
  'docs/对话与检索/semantic-cache.md',
  'docs/对话与检索/nl2sql-guide.md',
  'docs/对话与检索/知识库文档更新与存储.md',
  'docs/对话与检索/memory-guide.md',
  'docs/对话与检索/model-cascade.md',
  'docs/Agent编排/agent-guide.md',
  'docs/Agent编排/意图识别与Agent执行原理.md',
  'docs/Agent编排/workflow-guide.md',
  'docs/Agent编排/让Agent主动调接口.md',
  'docs/平台工程/eval-guide.md',
  'docs/平台工程/observability-guide.md',
  'docs/平台工程/litellm-gateway-guide.md',
  'docs/平台工程/rbac-and-public-kb.md',
  'docs/平台工程/eventbus-guide.md',
  'docs/互操作渠道/a2a-guide.md',
  'docs/互操作渠道/mcp-guide.md',
  'docs/参考/架构文档.md',
];

async function callModel(prompt) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const response = await fetch(liteLlmUrl, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${liteLlmKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          model: 'chat-default',
          temperature: 0.1,
          messages: [
            {
              role: 'system',
              content: '你是技术文档评测集标注员。只输出严格 JSON，不要 Markdown 代码块。',
            },
            {role: 'user', content: prompt},
          ],
        }),
      });
      const raw = await response.text();
      if (!response.ok) throw new Error(`HTTP ${response.status}: ${raw.slice(0, 500)}`);
      const envelope = JSON.parse(raw);
      const content = envelope.choices?.[0]?.message?.content || '';
      const start = content.indexOf('[');
      const end = content.lastIndexOf(']');
      if (start < 0 || end < start) throw new Error(`no JSON array: ${content.slice(0, 500)}`);
      return JSON.parse(content.slice(start, end + 1));
    } catch (error) {
      lastError = error;
      if (attempt < 3) await new Promise(resolve => setTimeout(resolve, 1000 * attempt));
    }
  }
  throw lastError;
}

function assertGenerated(items, source) {
  if (!Array.isArray(items) || items.length !== 10) {
    throw new Error(`${source}: expected 10 items, got ${items?.length}`);
  }
  for (const [index, item] of items.entries()) {
    if (!item || typeof item.question !== 'string' || item.question.trim().length < 6) {
      throw new Error(`${source}: invalid question at ${index}`);
    }
    if (typeof item.referenceAnswer !== 'string' || item.referenceAnswer.trim().length < 4) {
      throw new Error(`${source}: invalid referenceAnswer at ${index}`);
    }
  }
}

async function buildRagDataset() {
  const manifest = [];
  const evalCases = [];
  const metadata = [];
  let sequence = 1;

  for (const relativePath of corpus) {
    const absolutePath = path.join(root, relativePath);
    const text = await fs.readFile(absolutePath, 'utf8');
    const displayName = path.basename(relativePath);
    const excerpt = text.slice(0, 18000);
    const prompt = `
根据下面这份项目技术文档生成恰好 10 条中文检索评测问题。

要求：
1. 每条答案必须能仅由该文档支持，不引入外部知识。
2. 至少 4 条使用同义改写，避免直接复制标题。
3. 至少 2 条需要综合文档内两个信息点。
4. 问题在脱离文件名后仍清楚。
5. referenceAnswer 简短列出判定所需事实，不能捏造。
6. 输出 JSON 数组，每项仅包含 question、referenceAnswer。

文档路径：${relativePath}
文档内容：
${excerpt}
`;
    const generated = await callModel(prompt);
    assertGenerated(generated, relativePath);

    manifest.push({
      source: relativePath,
      displayName,
      bytes: Buffer.byteLength(text),
      sha256: await sha256(text),
    });
    for (const item of generated) {
      const id = `rag-${String(sequence++).padStart(3, '0')}`;
      evalCases.push({id, question: item.question.trim(), relevantDocIds: [displayName]});
      metadata.push({
        id,
        source: relativePath,
        displayName,
        question: item.question.trim(),
        referenceAnswer: item.referenceAnswer.trim(),
        generation: 'deepseek-generated-source-validated',
      });
    }
    process.stdout.write(`generated ${displayName}: 10\n`);
  }

  if (new Set(evalCases.map(item => item.id)).size !== 200) {
    throw new Error('RAG ids are not unique');
  }
  await fs.writeFile(
    path.join(datasetsDir, 'rag-corpus-manifest.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
  );
  await fs.writeFile(
    path.join(datasetsDir, 'rag-golden-200.json'),
    `${JSON.stringify({topK: 10, cases: evalCases}, null, 2)}\n`,
  );
  await fs.writeFile(
    path.join(datasetsDir, 'rag-cases-metadata.json'),
    `${JSON.stringify(metadata, null, 2)}\n`,
  );
}

async function sha256(text) {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Buffer.from(digest).toString('hex');
}

const nl2sqlIntents = [
  {id: 'may-order-count', expected: {kind: 'scalar', value: '7'}, variants: [
    '2026年5月一共有多少笔订单？', '统计一下2026年5月份的订单数量。', '五月份订单总笔数是多少？',
    '帮我数一下2026年五月的订单。', '2026-05期间产生了几张订单？', '查询五月订单数。',
    '2026年5月总共下了多少单？', '5月份的订单记录共有几条？', '请给出2026年5月订单数量。', '五月订单量是多少？',
  ]},
  {id: 'may-order-sum', expected: {kind: 'scalar', value: '13700.00'}, variants: [
    '2026年5月订单总金额是多少？', '统计五月份所有订单的金额合计。', '2026-05的订单流水总额是多少？',
    '五月订单金额加起来有多少？', '查询2026年五月订单总额。', '2026年5月全部订单（含所有状态）的金额合计是多少？',
    '帮我汇总五月份订单金额。', '2026年5月所有状态订单的amount合计是多少？', '返回2026年5月订单amount之和。', '5月订单总金额请告诉我。',
  ]},
  {id: 'may-refunded-order-count', expected: {kind: 'scalar', value: '3'}, variants: [
    '2026年5月状态为已退款的订单有多少笔？', '五月份已退款订单数量是多少？', '统计2026-05已退款状态订单数。',
    '五月有几张订单已经退款？', '查询五月订单中已退款的数量。', '2026年5月orders表中状态为已退款的记录有几条？',
    '5月订单状态等于已退款的记录数。', '2026年五月已退款订单量是多少？', '请数一下五月的已退款订单。', '2026年五月orders表中status=已退款的数量。',
  ]},
  {id: 'may-refunded-order-sum', expected: {kind: 'scalar', value: '6650.00'}, variants: [
    '2026年5月已退款订单金额合计是多少？', '五月状态为已退款的订单总额。', '统计2026-05已退款订单amount之和。',
    '五月退款订单涉及多少订单金额？', '查询五月已退款订单总金额。', '2026年5月orders表中已退款状态的amount加总。',
    '2026年五月orders表里所有已退款订单合计多少钱？', '5月订单状态为已退款的金额总计。', '请汇总五月退款订单金额。', '五月已退款订单总额是多少？',
  ]},
  {id: 'may-approved-refund-sum', expected: {kind: 'scalar', value: '6650.00'}, variants: [
    '2026年5月审批通过的退款金额是多少？', '五月approved退款金额合计。', '统计2026-05状态为approved的退款总额。',
    '五月已批准退款一共多少钱？', '查询五月退款表中approved记录的金额之和。', '2026年5月审批通过退款总金额。',
    '五月批准的退款款项加起来多少？', '5月refunds中approved的amount总计。', '请汇总五月成功审批的退款金额。', '五月通过审批的退款金额是多少？',
  ]},
  {id: 'pending-refund-sum', expected: {kind: 'scalar', value: '300.00'}, variants: [
    '待审批退款金额合计是多少？', 'pending状态退款共有多少钱？', '统计所有待处理退款的金额。',
    '退款表里pending记录金额之和。', '当前待审批退款总额。', '还有多少退款金额处于pending？',
    '请汇总尚未审批的退款金额。', '待处理退款的amount总计。', '查询pending退款总金额。', '待审批退款一共多少钱？',
  ]},
  {id: 'top-customer-may-orders', expected: {kind: 'contains', values: ['赵六', '5550.00']}, variants: [
    '2026年5月订单金额最高的客户是谁，总额多少？', '2026年5月按全部订单状态统计，订单金额最多的客户及金额。', '按五月订单总额找第一名客户。',
    '2026-05客户订单金额排行第一是谁？', '2026年五月包含所有状态订单时，金额贡献最大的客户。', '查询2026年五月全部状态订单金额最高客户和合计。',
    '5月哪位客户订单总金额最多？', '五月客户订单总额Top1。', '2026年五月下单金额最高的人是谁？', '返回五月订单额最大的客户及金额。',
  ]},
  {id: 'may-order-average', expected: {kind: 'numberTolerance', value: '1957.142857', tolerance: '0.01'}, variants: [
    '2026年5月平均每笔订单金额是多少？', '计算五月订单平均金额。', '2026-05订单amount平均值。',
    '2026年五月全部状态订单的amount平均值是多少？', '查询五月订单金额均值。', '2026年5月每单平均多少钱？',
    '五月份订单的平均金额。', '2026年5月全部订单（不筛状态）的均价是多少？', '请计算2026年五月订单平均值。', '五月平均订单金额请告诉我。',
  ]},
  {id: 'east-customer-count', expected: {kind: 'scalar', value: '2'}, variants: [
    '华东地区有多少客户？', '统计客户大区为华东的数量。', 'region是华东的客户共有几个？',
    '查询华东客户数。', '华东区域客户数量是多少？', '请数一下华东客户。',
    '客户表里华东记录有几条？', '当前华东大区有多少位客户？', '统计region=华东。', '华东客户总数。',
  ]},
  {id: 'april-order-sum', expected: {kind: 'scalar', value: '2740.00'}, variants: [
    '2026年4月订单总金额是多少？', '统计四月份所有订单金额。', '2026-04订单流水合计。',
    '四月订单金额加起来多少？', '查询2026年四月订单总额。', '2026年4月全部状态订单金额之和。',
    '帮我汇总四月份订单amount。', '4月份下单总金额。', '返回2026年4月订单金额之和。', '2026年四月全部状态订单总额是多少？',
  ]},
  {id: 'refund-count', expected: {kind: 'scalar', value: '6'}, variants: [
    '一共有多少条退款记录？', '统计退款总笔数。', 'refunds表记录数是多少？', '查询全部退款数量。',
    '当前共有几笔退款申请？', '退款记录总数。', '请数一下所有退款。', '总共有多少个退款单？', '退款表有几行数据？', '全部退款笔数是多少？',
  ]},
  {id: 'rejected-refund-sum', expected: {kind: 'scalar', value: '120.00'}, variants: [
    '被拒绝的退款金额合计是多少？', 'rejected状态退款总额。', '统计所有拒绝退款的金额。',
    '退款表中rejected记录amount之和。', '审批拒绝的退款一共多少钱？', '查询被驳回退款金额。',
    '拒绝状态的退款总金额。', '请汇总rejected退款。', 'status明确等于rejected的退款金额合计。', '被拒退款总额是多少？',
  ]},
  {id: 'customers-with-refunds', expected: {kind: 'scalar', value: '4'}, variants: [
    '有过退款记录的客户有多少个？', '统计发生过退款的不同客户数。', 'refunds里去重customer_id有几个？',
    '共有多少位客户申请过退款？', '查询退款客户数量。', '发生退款的独立客户数。',
    '请数一下有退款记录的客户。', '退款涉及多少个不同客户？', '有过refund的客户总数。', '退款客户去重后有几位？',
  ]},
  {id: 'paid-order-sum', expected: {kind: 'scalar', value: '5390.00'}, variants: [
    '已支付订单金额合计是多少？', '状态为已支付的订单总额。', '统计所有已支付订单amount的总和。',
    '查询已支付订单金额之和。', '已支付的订单一共多少钱？', '订单表中已支付状态总金额。',
    '请汇总已支付订单。', '支付完成订单金额合计。', 'status=已支付的订单总额。', '已支付订单流水是多少？',
  ]},
  {id: 'empty-june-2025', expected: {kind: 'scalar', value: '0'}, variants: [
    '2025年6月有多少笔订单？', '统计2025-06订单数。', '去年六月订单数量是多少？',
    '查询2025年6月份订单记录数。', '2025六月有订单吗，给出数量。', '2025-06期间下了几单？',
    '请数一下2025年六月订单。', '2025年6月订单共有几条？', '返回2025-06订单数量。', '二零二五年六月订单量。',
  ]},
];

async function buildNl2SqlDataset() {
  const cases = [];
  for (const intent of nl2sqlIntents) {
    for (const [index, question] of intent.variants.entries()) {
      cases.push({
        id: `${intent.id}-${String(index + 1).padStart(2, '0')}`,
        intent: intent.id,
        question,
        expected: intent.expected,
        tenant: 'tenantA',
      });
    }
  }
  if (cases.length !== 150) throw new Error(`expected 150 NL2SQL cases, got ${cases.length}`);
  await fs.writeFile(
    path.join(datasetsDir, 'nl2sql-golden-150.json'),
    `${JSON.stringify(cases, null, 2)}\n`,
  );
}

const agentGoals = [
  ['rag-architecture', '说明本平台RAG从文档入库到带引用回答的完整链路，并指出至少两项质量优化措施',
    ['文档', '分块', '向量', 'BM25', 'RRF', '引用'], ['所有检索只依赖关键词']],
  ['tenant-isolation', '设计本平台知识库多租户隔离验证方案，覆盖身份传播、存储分区、负向测试和观测',
    ['JWT', 'tenant', '分区', '跨租户', '测试'], []],
  ['nl2sql-security', '总结NL2SQL安全护栏并解释每层分别防止什么风险',
    ['SELECT', '白名单', 'LIMIT', '超时', 'tenant'], []],
  ['agent-dag', '解释多Agent DAG如何进行拓扑分层、同层并行、结果汇总和失败重规划',
    ['拓扑', '并行', 'synthesis', 'critic', 'replan'], []],
  ['observability', '为一次RAG慢查询制定从网关到模型调用的可观测性排查步骤',
    ['trace', '网关', '检索', '模型', 'P95'], []],
  ['cache-consistency', '分析语义缓存的收益、误命中风险、租户隔离和知识库更新后的失效策略',
    ['命中', '阈值', '租户', '失效', '更新'], []],
  ['rag-evaluation', '制定RAG离线评测方案，说明Recall@K、Precision@K、MRR和引用指标的口径',
    ['Recall', 'Precision', 'MRR', '引用', '黄金集'], []],
  ['model-gateway', '说明LiteLLM网关在模型路由、故障转移、缓存和成本归因中的职责',
    ['路由', '故障', '缓存', '成本'], []],
  ['document-update', '解释同名知识库文档更新时旧版本如何被替换以及各存储如何保持一致',
    ['docId', '版本', '向量', 'Elasticsearch', '删除'], []],
  ['public-kb', '比较租户私有知识库和公共知识库的读写权限与检索合并规则',
    ['私有', '公共', '权限', '检索', '__public__'], []],
  ['eventbus', '设计一个跨服务异步事件处理方案，包含投递、幂等、重试和可观测性',
    ['事件', '幂等', '重试', '观测'], []],
  ['workflow-human', '说明退款审批流程中的Agent、人类审批和工作流状态机如何协作',
    ['Agent', '审批', '状态', '人工'], []],
  ['memory-design', '比较短期对话记忆和长期用户画像的存储、隔离与使用场景',
    ['短期', '长期', '存储', '租户'], []],
  ['a2a-mcp', '比较A2A与MCP在本平台的定位、通信对象和适用场景',
    ['A2A', 'MCP', 'Agent', '工具'], []],
  ['rate-limit', '设计按租户和接口族实施限流与预算控制的方案',
    ['租户', '接口', '限流', '预算'], []],
  ['rag-failure', '列出向量库、Elasticsearch和重排器分别不可用时RAG应如何降级',
    ['向量', 'Elasticsearch', '重排', '降级'], []],
  ['sql-correctness', '制定NL2SQL正确率评测流程，区分执行成功与结果正确',
    ['执行', '结果', '黄金', '标准化'], []],
  ['agent-quality', '制定多Agent复杂任务完成率评测方案，避免模型自己生成又自己评分的偏差',
    ['Rubric', '确定性', 'Critic', '偏差'], []],
  ['deployment', '给出本平台从本地Compose到生产部署的配置与密钥治理检查清单',
    ['Compose', '配置', '密钥', '生产'], []],
  ['resume-metrics', '为RAG、Agent和NL2SQL设计可写进简历且可复现的指标证据包',
    ['RAG', 'Agent', 'NL2SQL', '测试集', '报告'], []],
].map(([id, goal, required, forbidden]) => ({id, goal, required, forbidden}));

async function buildAgentDataset() {
  await fs.writeFile(
    path.join(datasetsDir, 'agent-goals-20.json'),
    `${JSON.stringify(agentGoals, null, 2)}\n`,
  );
}

await fs.mkdir(datasetsDir, {recursive: true});
await Promise.all([buildNl2SqlDataset(), buildAgentDataset()]);
await buildRagDataset();
console.log('datasets prepared');
