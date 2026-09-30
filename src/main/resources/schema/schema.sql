-- ============================================================
-- 游戏域数仓 DDL（SQLite）
-- 时间锚：库里"今天" = 2026-01-31，数据覆盖 2025-11-01 ~ 2026-01-31
-- 命名：表名/列名一律单数缩写；金额单位一律「元」（REAL）
-- 本文件是字段的唯一权威定义源，勿在库里另建视图改口径
-- ============================================================

-- 区服字典
DROP TABLE IF EXISTS srv;
CREATE TABLE srv (
  srv_id    INTEGER PRIMARY KEY,          -- 区服ID
  srv_name  TEXT      NOT NULL,           -- 区服名，中文；注意与 srv_id 大小不同序
  open_dt   TEXT      NOT NULL,           -- 开服日期 YYYY-MM-DD
  srv_st    INTEGER   NOT NULL,           -- 区服状态 1=开 0=关；不是布尔值
  plat      TEXT      NOT NULL            -- 平台 and=安卓 ios=苹果 pc=PC端
);

-- 账号
DROP TABLE IF EXISTS acct;
CREATE TABLE acct (
  acct_id       INTEGER PRIMARY KEY,      -- 账号ID
  chan_id       TEXT      NOT NULL,       -- 买量渠道 yx=游戏联运 tt=字节系 gw=官网包 ios=苹果商店
  reg_time      TEXT      NOT NULL,       -- 注册时间 YYYY-MM-DD HH:mm:ss（带时分秒，与其它日期列格式不同）
  first_pay_dt  TEXT,                     -- 首次付费日期 YYYY-MM-DD；NULL 表示从未付费
  last_login_dt TEXT      NOT NULL,       -- 最近登录日期 YYYY-MM-DD
  is_del        INTEGER   NOT NULL        -- 是否注销 1=已注销 0=正常
);

-- 角色
DROP TABLE IF EXISTS role;
CREATE TABLE role (
  role_id     INTEGER PRIMARY KEY,        -- 角色ID
  acct_id     INTEGER     NOT NULL,       -- 所属账号ID
  srv_id      INTEGER     NOT NULL,       -- 所在区服ID
  role_no     INTEGER     NOT NULL,       -- 该账号在该区服内的第几个角色，从1开始
  create_time TEXT        NOT NULL,       -- 创角时间 YYYY-MM-DD HH:mm:ss
  lv          INTEGER     NOT NULL,       -- 等级
  vip_lv      INTEGER     NOT NULL,       -- VIP等级 0=非会员（不是NULL）
  is_del        INTEGER   NOT NULL        -- 是否删号 1=已删 0=正常
);

-- 商品字典
DROP TABLE IF EXISTS goods;
CREATE TABLE goods (
  goods_id   INTEGER PRIMARY KEY,         -- 商品ID
  goods_name TEXT      NOT NULL,          -- 商品名
  price_yuan REAL      NOT NULL,          -- 售价，单位：元（REAL，求和会有尾差）
  goods_type INTEGER   NOT NULL,          -- 商品类型 1=月卡 2=战令 3=直购 6=首充
  is_on        INTEGER NOT NULL            -- 是否在售 1=在售 0=已下架
);

-- 付费订单
DROP TABLE IF EXISTS pay_ord;
CREATE TABLE pay_ord (
  ord_no    TEXT      PRIMARY KEY,        -- 订单号
  acct_id   INTEGER   NOT NULL,           -- 付费账号ID
  srv_id    INTEGER   NOT NULL,           -- 付费时所在区服ID
  goods_id  INTEGER   NOT NULL,           -- 商品ID
  pay_amt   REAL      NOT NULL,           -- 实付金额，单位：元；退款单不减负、仍是正数
  pay_time  TEXT      NOT NULL,           -- 支付时间 YYYY-MM-DD HH:mm:ss
  ord_st    INTEGER   NOT NULL,           -- 订单状态 1=成功 2=退款 3=失败；算净付费必须排除2和3
  is_del    INTEGER   NOT NULL            -- 是否删除 1=已删 0=正常
);

-- 日粒度活跃日志（本表没有 is_del，别加这个过滤条件）
DROP TABLE IF EXISTS login_log;
CREATE TABLE login_log (
  acct_id   INTEGER   NOT NULL,           -- 账号ID
  srv_id    INTEGER   NOT NULL,           -- 区服ID
  login_dt  TEXT      NOT NULL,           -- 登录日期 YYYY-MM-DD
  login_cnt INTEGER   NOT NULL,           -- 当日登录次数
  dur_sec   INTEGER   NOT NULL,           -- 当日在线时长，单位：秒
  PRIMARY KEY (acct_id, srv_id, login_dt)
);

-- 道具变更流水（本表没有 is_del，也没有 srv_id；要按区服统计必须经 role 两跳 JOIN）
DROP TABLE IF EXISTS item_flow;
CREATE TABLE item_flow (
  flow_id     INTEGER PRIMARY KEY,        -- 流水ID
  role_id     INTEGER   NOT NULL,         -- 角色ID；到账号需 JOIN role
  item_id     INTEGER   NOT NULL,         -- 道具ID
  chg_type    INTEGER   NOT NULL,         -- 变更类型 1=获得 2=消耗 3=系统补发 4=交易
  chg_num     INTEGER   NOT NULL,         -- 变更数量，有正有负（消耗为负），不要套 ABS()
  create_time TEXT      NOT NULL          -- 变更时间 YYYY-MM-DD HH:mm:ss
);

-- 赛季（这两个日期列的命名风格与全库其它列不一致，是历史遗留）
DROP TABLE IF EXISTS season;
CREATE TABLE season (
  season_id INTEGER PRIMARY KEY,          -- 赛季ID
  srv_id    INTEGER   NOT NULL,           -- 区服ID
  st_dt     TEXT      NOT NULL,           -- 赛季开始日 YYYY-MM-DD
  ed_dt     TEXT      NOT NULL            -- 赛季结束日 YYYY-MM-DD
);
