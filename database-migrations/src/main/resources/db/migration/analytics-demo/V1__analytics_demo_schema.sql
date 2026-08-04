CREATE TABLE IF NOT EXISTS customers (
  id INT NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户 id，所有查询必须按此过滤',
  name VARCHAR(128) NOT NULL,
  region VARCHAR(32) NOT NULL COMMENT '客户所在大区：华东 / 华北 / 华南 / 西南',
  created_at DATE NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
  id INT NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户 id，所有查询必须按此过滤',
  customer_id INT NOT NULL,
  amount DECIMAL(12,2) NOT NULL COMMENT '订单金额，单位元',
  status VARCHAR(16) NOT NULL COMMENT '订单状态（中文枚举）：已支付 / 已发货 / 已取消 / 已退款',
  created_at DATE NOT NULL
);

CREATE TABLE IF NOT EXISTS refunds (
  id INT NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户 id，所有查询必须按此过滤',
  order_id INT NOT NULL,
  customer_id INT NOT NULL,
  amount DECIMAL(12,2) NOT NULL COMMENT '退款金额，单位元',
  reason VARCHAR(128),
  status VARCHAR(16) NOT NULL COMMENT '退款审批状态（英文枚举）：pending / approved / rejected',
  created_at DATE NOT NULL
);
