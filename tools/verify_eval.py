#!/usr/bin/env python3
"""评测集期望值的唯一计算路径：truthSql 在 data/game.db 上真跑，数字直接写进 JSON。

用法：
  python tools/verify_eval.py build   # 按 BUSINESS 定义生成 src/main/resources/eval/business.json
  python tools/verify_eval.py check   # 重跑两个评测集里所有 truthSql，与文件里的期望值比对
"""
import json, sqlite3, sys

DB = 'data/game.db'
SEED_SHA = '57ea3ee342e5ae703d68dd91257e3c4d06a6a3bc51773276e0b4541dab4778f7'
OUT = 'src/main/resources/eval/business.json'
FILES = ['src/main/resources/eval/adversarial.json', OUT]

# expect.kind: scalar（单个数字，金额带 tol=0.01）/ rowcount（行数或计数）
BUSINESS = [
 dict(id='B01', layer='单表', retention=True,
      trap='login_log 无 is_del；同一账号同日多服有多条，DAU 必须去重',
      question='1月31号有多少个账号登录过？',
      caliber='DAU = login_log 中 login_dt=2026-01-31 的去重 acct_id 数；同一账号当天在多个区服登录只算一次。本表没有 is_del，不需要也不能加删除过滤。',
      truthSql="select count(distinct acct_id) from login_log where login_dt='2026-01-31'",
      expect=dict(kind='rowcount'),
      ifTrapMissed="不去重 count(*) → 137（多服账号被重复计入）；加 WHERE is_del=0 → no such column: is_del，本表没这列"),
 dict(id='B02', layer='单表', retention=True,
      trap='login_cnt 是"每条日志一行"，跨服不自动累加',
      question='1月31号所有账号加起来一共登录了多少次？',
      caliber='= 该日 login_log 全部行的 login_cnt 之和（含同一账号在多服的多次记录），不做去重。',
      truthSql="select sum(login_cnt) from login_log where login_dt='2026-01-31'",
      expect=dict(kind='scalar'),
      ifTrapMissed='误用 count(*) 代替 sum(login_cnt) → 137（那是当日日志行数，不是登录次数）'),
 dict(id='B03', layer='单表', retention=False,
      trap='login_cnt 按 (acct_id,srv_id,login_dt) 存，跨服账号要先决定是否合并',
      question='1月31号那天，登录次数超过3次的账号有多少个？',
      caliber='按单条日志判定：存在 login_cnt>3 的记录即算该账号命中，账号去重。不把同一账号跨区的 login_cnt 相加。',
      truthSql="select count(distinct acct_id) from login_log where login_dt='2026-01-31' and login_cnt>3",
      expect=dict(kind='rowcount'),
      ifTrapMissed='按账号跨区合并 login_cnt 后再判 >3 → 65（把两个服各 2 次拼成 4 次）'),
 dict(id='B04', layer='聚合分组', retention=True,
      trap='先按日去重再对天数取平均，不是总记录数/7',
      question='1月25号到31号这7天，平均每天有多少个账号活跃？保留两位小数。',
      caliber='平均 DAU：先对窗口内每个 login_dt 求去重账号数，再对这些天取算术平均，ROUND(,2)。窗口 [2026-01-25, 2026-02-01)。本窗口 7 天每天都有记录，不存在缺日补零问题。',
      truthSql="select round(avg(d),2) from (select login_dt, count(distinct acct_id) d from login_log where login_dt>='2026-01-25' and login_dt<'2026-02-01' group by login_dt)",
      expect=dict(kind='scalar', tol=0.01),
      ifTrapMissed='不去重、按日志行数取日均 → 134.14（窗口正好 7 天，所以"行数/7"得同一个错值）'),
 dict(id='B05', layer='聚合分组', retention=True,
      trap='次留的窗口是"注册日+1"，不是"注册日所在自然周"',
      question='1月2号到1月8号注册的这批新账号，次日留存是多少个百分点？保留两位小数。',
      caliber='按注册日分组：分母 = 该日注册账号数，分子 = 其中在 login_log 有 login_dt = 注册日+1 的账号数；再把 7 天的分子求和 / 分母求和（加权，不是各日留存率的算术平均），乘 100 后 ROUND(,2)。不过滤 is_del。',
      truthSql="select round(100.0*sum(hit)/sum(cnt),2) from (select substr(a.reg_time,1,10) d, count(*) cnt, sum(exists(select 1 from login_log l where l.acct_id=a.acct_id and l.login_dt=date(a.reg_time,'+1 day'))) hit from acct a where substr(a.reg_time,1,10) between '2026-01-02' and '2026-01-08' group by d)",
      expect=dict(kind='scalar', tol=0.01),
      ifTrapMissed='各日留存率取算术平均 → 63.47（本口径要的是加权 27/40）；把 reg_time 当纯日期列直接相等比较 → 0 命中，该列带时分秒'),
 dict(id='B06', layer='多表JOIN', retention=False,
      trap='赛季边界只能从 season 表读；srv_name 的 S 序号与 srv_id 不同序',
      question='S3区-苍梧第一个赛季期间的净付费是多少？',
      caliber='S3区-苍梧 = srv_id 101（不是 103）。第一赛季 = season 表里该服 st_dt/ed_dt 的第一段（2025-11-01~2025-12-31）。净付费 = ord_st=1 且 is_del=0 的 pay_amt 之和，订单日期取 date(pay_time) 落在闭区间 [st_dt, ed_dt]。ROUND(,2)。',
      truthSql="select round(sum(o.pay_amt),2) from pay_ord o join season s on s.srv_id=o.srv_id and s.season_id=1 where o.srv_id=101 and date(o.pay_time) between s.st_dt and s.ed_dt and o.ord_st=1 and o.is_del=0",
      expect=dict(kind='scalar', tol=0.01),
      ifTrapMissed='把"S3区-苍梧"当成 srv_id=103 → 28297.80。另记：第一赛季的边界正好等于 11+12 两个自然月，所以不 JOIN season 而按这两月猜边界会碰巧同值 —— 本题实际只卡区服名，不卡赛季'),
 dict(id='B07', layer='多表JOIN', retention=True,
      trap='chan_id 是拼音缩写，含义只在列注释里；老账号要按注册日切',
      question='上周（1月19到1月25）在游戏联运渠道注册的账号里，有多少个真的登录过？',
      caliber='游戏联运 = acct.chan_id=’yx’（映射见 acct 列注释）。活跃 = 该账号在 login_log 于 [2026-01-19, 2026-01-26) 有任意记录，去重计数。不筛 acct.is_del，也不区分注册时段。',
      truthSql="select count(distinct l.acct_id) from login_log l join acct a on a.acct_id=l.acct_id where a.chan_id='yx' and l.login_dt>='2026-01-19' and l.login_dt<'2026-01-26'",
      expect=dict(kind='rowcount'),
      ifTrapMissed="渠道含义靠猜（yx 猜成'优量汇'之类）→ 集合整个换掉。另记一笔：加'1月19号之前注册'的老账号条件得数不变（仍是 100），因为这批人全是窗口前注册的 —— 数据若重生成，这题的'老账号'变体可能是空操作，别当成有效区分度"),
 dict(id='B08', layer='多表JOIN', retention=False,
      trap='goods_type 是数字枚举（2=战令），且退款单仍是正数',
      question='1月战令类商品一共收了多少？',
      caliber='战令 = goods.goods_type=2，经 goods_id JOIN。净收入 = ord_st=1 且 is_del=0，pay_time ∈ [2026-01-01, 2026-02-01)。ROUND(,2)。',
      truthSql="select round(sum(o.pay_amt),2) from pay_ord o join goods g on g.goods_id=o.goods_id where g.goods_type=2 and o.ord_st=1 and o.is_del=0 and o.pay_time>='2026-01-01' and o.pay_time<'2026-02-01'",
      expect=dict(kind='scalar', tol=0.01),
      ifTrapMissed='不过滤 ord_st → 18306.40（退款与失败单被计成收入）。注：goods_type=’2’ 写成字符串在 SQLite 里不会漏行（INTEGER 列做了强制转换），别把它当失分点'),
 dict(id='B09', layer='多表JOIN', retention=False,
      trap='首充是商品类型 6，不是"账号的首次付费"',
      question='1月买过首充类商品的账号有多少个？',
      caliber='首充类 = goods.goods_type=6（首充大礼包、首充双倍两个商品），不是 acct.first_pay_dt。账号去重；订单口径 ord_st=1 且 is_del=0；时间 [2026-01-01, 2026-02-01)。',
      truthSql="select count(distinct o.acct_id) from pay_ord o join goods g on g.goods_id=o.goods_id where g.goods_type=6 and o.ord_st=1 and o.is_del=0 and o.pay_time>='2026-01-01' and o.pay_time<'2026-02-01'",
      expect=dict(kind='rowcount'),
      ifTrapMissed='理解成"1月首次付费的账号"→ 走 acct.first_pay_dt，得 48（另一批人，不是同一集合）'),
 dict(id='B10', layer='多表JOIN', retention=True,
      trap='dur_sec 单位是秒；"最长"要按区服聚合后排序',
      question='1月在线总时长最长的区服，总时长是多少小时？保留两位小数。',
      caliber='按 srv_id 对 login_log 的 dur_sec 求和，取最大者；小时 = 秒/3600，ROUND(,2)。窗口 [2026-01-01, 2026-02-01) 按 login_dt。区服名不影响答案，故不断言是哪一区。',
      truthSql="select round(sum(l.dur_sec)/3600.0,2) from login_log l join srv s on s.srv_id=l.srv_id where l.login_dt>='2026-01-01' and l.login_dt<'2026-02-01' group by l.srv_id order by sum(l.dur_sec) desc limit 1",
      expect=dict(kind='scalar', tol=0.01),
      ifTrapMissed='忘了 /3600 直接报秒 → 10729492（同一个服的正确值折算成秒）；用 sum(login_cnt) 代替 sum(dur_sec) → 另一个指标'),
 dict(id='T01', layer='追问', retention=False,
      trap='追问只给时间条件变化，区服与订单口径要沿用上一轮',
      turns=['101区服上周（1月19到1月25）净付费是多少？', '再往前一周呢？'],
      caliber='第2轮沿用第1轮的 srv_id=101 与净付费口径（ord_st=1 且 is_del=0），只把窗口平移到 [2026-01-12, 2026-01-19)。断言第2轮的答案。',
      truthSql="select round(sum(pay_amt),2) from pay_ord where srv_id=101 and ord_st=1 and is_del=0 and pay_time>='2026-01-12' and pay_time<'2026-01-19'",
      expect=dict(kind='scalar', tol=0.01),
      ifTrapMissed='窗口没平移、重复上一轮 → 500.00；区服丢了变成全服 → 8200.00'),
 dict(id='T02', layer='追问', retention=False,
      trap='first_pay_dt 为 NULL 才是"没付过费"，指代要靠上一轮的"全部账号"',
      turns=['一共有多少个账号注册过？', '其中到1月底为止付过费的占多少个百分点？'],
      caliber='第2轮分母 = 全部 acct 行（含 is_del=1 的注销账号，口径在此明确声明为不剔除）；分子 = first_pay_dt 非空；乘 100 后 ROUND(,2)。断言第2轮。',
      truthSql="select round(100.0*count(first_pay_dt)/count(*),2) from acct",
      expect=dict(kind='scalar', tol=0.01),
      ifTrapMissed="叠 is_del=0 → 52.35（分母变 277，反而**变低**：注销的 23 个账号里付费比例更高）。注意本题没有第二种失分法：把'付过费'理解成'有成功订单'实测同为 53.33，first_pay_dt 与订单在这份数据里完全一致，所以这题只考 NULL 处理和指代继承，别指望它区分口径"),
 dict(id='T03', layer='追问', retention=True,
      trap='收窄维度时日期条件必须保留，平台在 srv 表不在 acct 表',
      turns=['1月31号的DAU是多少？', '其中在安卓平台区服的有多少个账号？'],
      caliber='第2轮沿用 login_dt=2026-01-31，追加 srv.plat=’and’（101/104/105 三个服），账号去重。注意一个账号当天可能既在安卓服也在iOS服，本口径只要它在安卓服出现过就算。',
      truthSql="select count(distinct l.acct_id) from login_log l join srv s on s.srv_id=l.srv_id where l.login_dt='2026-01-31' and s.plat='and'",
      expect=dict(kind='rowcount'),
      ifTrapMissed='丢掉日期变成全月 and 服去重 → 209；只数 and 服的日志行不去重 → 81'),
 dict(id='T04', layer='追问', retention=False,
      trap='第2轮的对象由第1轮的排序结果决定，必须解析"那个服"是哪一个',
      turns=['1月净付费最高的是哪个区服？', '把那个服1月的月卡收入单独给我。'],
      caliber='第1轮：ord_st=1 且 is_del=0、pay_time ∈ [2026-01-01,2026-02-01)，按 srv_id 求和取最大 —— 实测最高为 srv_id=102（S1区-龙渊）。第2轮沿用 102 与同一净付费口径，再限 goods_type=1（月卡）。断言第2轮金额。',
      truthSql="select round(sum(o.pay_amt),2) from pay_ord o join goods g on g.goods_id=o.goods_id where o.srv_id=(select srv_id from pay_ord where ord_st=1 and is_del=0 and pay_time>='2026-01-01' and pay_time<'2026-02-01' group by srv_id order by sum(pay_amt) desc limit 1) and g.goods_type=1 and o.ord_st=1 and o.is_del=0 and o.pay_time>='2026-01-01' and o.pay_time<'2026-02-01'",
      expect=dict(kind='scalar', tol=0.01),
      ifTrapMissed='第2轮丢掉净付费过滤 → 3132.00；指代丢失、误用第1轮金额 → 19037.02'),
 dict(id='T05', layer='追问', retention=True,
      trap='追加"老账号"条件时窗口与去重都必须沿用',
      turns=['上周（1月19到1月25）一共有多少个账号登录过？', '只看12月1号之前注册的老账号呢？'],
      caliber='第2轮沿用 [2026-01-19, 2026-01-26) 与去重账号数，追加 acct.reg_time < 2025-12-01。不筛 is_del。断言第2轮。',
      truthSql="select count(distinct l.acct_id) from login_log l join acct a on a.acct_id=l.acct_id where l.login_dt>='2026-01-19' and l.login_dt<'2026-01-26' and a.reg_time<'2025-12-01'",
      expect=dict(kind='rowcount'),
      ifTrapMissed='丢掉时间窗变成全量老账号 → 250。注：边界写 <=2025-12-01 与 < 同值（224），因为 12月1号当天无人注册，这条不构成区分度'),
]


def measure(con, sql):
    row = con.execute(sql).fetchone()
    return row[0] if row else None


def build():
    con = sqlite3.connect(DB)
    qs = []
    for item in BUSINESS:
        val = measure(con, item['truthSql'])
        if val is None:
            sys.exit(f'{item["id"]} 期望值为 None，题目退化，须换问法')
        exp = dict(item['expect'])
        exp['value'] = val
        q = {k: v for k, v in item.items() if k != 'expect'}
        q['expect'] = exp
        qs.append(q)
        print(f'{q["id"]:>4} {q["layer"]:<6} = {val}')
    doc = dict(
        set='business-15',
        owner='qoder',
        note=('这 15 道原计划由需求方写（真实会问的题），2026-09-30 由我代笔，'
              '因此 owner 标 qoder：口径是我对这份数据集的设定，不代表业务真实口径。'
              'README 的准确率表须注明这一点。期望值全部由 truthSql 在 data/game.db 上真跑得到，'
              '重算请用 python tools/verify_eval.py check。'),
        seedDataSha256=SEED_SHA,
        anchorToday='2026-01-31',
        assertPolicy='只断言数字/行数，绝不断言 SQL 字符串；金额一律 ±0.01 容差',
        layerCount={},
        retentionIds=[q['id'] for q in qs if q.get('retention')],
        questions=qs,
    )
    counts = {}
    for q in qs:
        counts[q['layer']] = counts.get(q['layer'], 0) + 1
    doc['layerCount'] = counts
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('layer 分布:', counts, '| 留存类:', doc['retentionIds'])


def check():
    con = sqlite3.connect(DB)
    bad = 0
    total = 0
    for path in FILES:
        doc = json.load(open(path, encoding='utf-8'))
        for q in doc['questions']:
            total += 1
            got = measure(con, q['truthSql'])
            want = q['expect']['value']
            tol = q['expect'].get('tol')
            ok = (abs(got - want) <= tol) if (tol is not None and got is not None) else (got == want)
            if not ok:
                bad += 1
                print(f'MISMATCH {q["id"]}: 期望 {want} 实测 {got}')
    print(f'check: {total - bad}/{total} 条期望值与当前数据库一致')
    return 1 if bad else 0


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'check'
    if mode == 'build':
        build()
    elif mode == 'check':
        sys.exit(check())
    else:
        sys.exit(f'unknown mode {mode}')
