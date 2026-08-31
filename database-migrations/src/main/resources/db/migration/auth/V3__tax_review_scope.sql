-- 为已有 JDBC 安装增加财税审查角色；所有写入均为幂等，不覆盖租户自定义角色。
INSERT INTO ROLES (NAME, SCOPES, DESCRIPTION, VERSION, CREATED_AT)
SELECT 'tax-analyst', 'chat,tax-review', '对话 + 财税发票风险审查', 0, 0
WHERE NOT EXISTS (SELECT 1 FROM ROLES WHERE NAME = 'tax-analyst');

INSERT INTO ROLE_SCOPE (ROLE_NAME, SCOPE, CREATED_AT)
SELECT 'tax-analyst', 'chat', 0
WHERE NOT EXISTS (
  SELECT 1 FROM ROLE_SCOPE WHERE ROLE_NAME = 'tax-analyst' AND SCOPE = 'chat'
);

INSERT INTO ROLE_SCOPE (ROLE_NAME, SCOPE, CREATED_AT)
SELECT 'tax-analyst', 'tax-review', 0
WHERE NOT EXISTS (
  SELECT 1 FROM ROLE_SCOPE WHERE ROLE_NAME = 'tax-analyst' AND SCOPE = 'tax-review'
);

-- 内建 admin 的语义是全权限；如果部署已删除或改名，该更新自然不生效。
INSERT INTO ROLE_SCOPE (ROLE_NAME, SCOPE, CREATED_AT)
SELECT 'admin', 'tax-review', 0
WHERE EXISTS (SELECT 1 FROM ROLES WHERE NAME = 'admin')
  AND NOT EXISTS (
    SELECT 1 FROM ROLE_SCOPE WHERE ROLE_NAME = 'admin' AND SCOPE = 'tax-review'
  );

-- ROLES.SCOPES 是一个发布周期内的回滚影子列，关系表 ROLE_SCOPE 仍是权威来源。
UPDATE ROLES
SET SCOPES = CASE
    WHEN SCOPES IS NULL OR TRIM(SCOPES) = '' THEN 'tax-review'
    ELSE CONCAT(SCOPES, ',tax-review')
  END,
  VERSION = VERSION + 1
WHERE NAME = 'admin'
  AND CONCAT(',', REPLACE(COALESCE(SCOPES, ''), ' ', ''), ',') NOT LIKE '%,tax-review,%';
