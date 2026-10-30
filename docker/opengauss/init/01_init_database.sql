-- openGauss 初始化脚本（首次创建数据卷时由容器入口执行）
-- 作用：创建业务库、统一编码、把 public schema 授权给业务账号

CREATE DATABASE lingkan WITH ENCODING = 'UTF8' DBCOMPATIBILITY = 'PG';

GRANT ALL PRIVILEGES ON DATABASE lingkan TO gaussdb;

\c lingkan

ALTER SCHEMA public OWNER TO gaussdb;
GRANT ALL ON SCHEMA public TO gaussdb;

COMMENT ON DATABASE lingkan IS '玲看股息 LingKan 业务库';
