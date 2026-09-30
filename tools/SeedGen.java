import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 游戏域 seed 生成器。纯 JDK，无需 Maven：java tools/SeedGen.java
 * 固定种子 ⇒ 逐字节可复现；任一不变量被破坏直接抛错，不产出"看起来对"的数据。
 */
public class SeedGen {

    static final long SEED = 20260131L;
    static final Random R = new Random(SEED);
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final LocalDate DATA_START = LocalDate.of(2025, 11, 1);
    static final LocalDate TODAY = LocalDate.of(2026, 1, 31);
    static final LocalDate W1_MON = LocalDate.of(2026, 1, 12);
    static final LocalDate W1_SUN = LocalDate.of(2026, 1, 18);
    static final LocalDate W2_MON = LocalDate.of(2026, 1, 19);
    static final LocalDate W2_SUN = LocalDate.of(2026, 1, 25);

    static final int ACCT_N = 300, ROLE_N = 450, ORD_TOTAL = 2500, LOGIN_TOTAL = 9000, FLOW_TOTAL = 12000;

    static final int[] SRV_IDS = {101, 102, 103, 104, 105};
    // srv_name 的 S 序号与 srv_id 刻意不同序；五服全在营，避免"停服仍在付费"这种无解口径
    static final String[] SRV_NAME = {"S3区-苍梧", "S1区-龙渊", "S5区-云梦", "S2区-星陨", "S4区-幽荒"};
    static final String[] SRV_PLAT = {"and", "ios", "pc", "and", "and"};
    static final String[] SRV_OPEN = {"2025-11-01", "2025-11-08", "2025-11-15", "2025-12-01", "2025-12-20"};

    // {srv_id, 01-12~01-18 净额(分), 01-19~01-25 净额(分)}
    // 101 = 百分比跌幅最大(-50%)，102 = 绝对额跌幅最大(-2000元)，两者必须不是同一个区服
    static final long[][] TARGET = {
        {101, 100000, 50000},
        {102, 500000, 300000},
        {103, 90000, 89000},
        {104, 70000, 65000},
        {105, 60000, 66000},
    };

    static final int[][] GOODS = {
        {1, 3000, 1, 1}, {2, 6800, 2, 1}, {3, 600, 6, 1}, {4, 100, 3, 1}, {5, 600, 3, 1},
        {6, 1288, 3, 1}, {7, 6800, 3, 1}, {8, 9800, 3, 1}, {9, 9800, 1, 1}, {10, 12800, 2, 1},
        {11, 64800, 3, 1}, {12, 32800, 3, 1}, {13, 19800, 3, 1}, {14, 1000, 1, 1}, {15, 6880, 2, 0},
        {16, 30, 3, 1}, {17, 660, 3, 1}, {18, 100, 6, 1}, {19, 18888, 3, 1}, {20, 128800, 2, 0},
    };
    static final String[] GOODS_NAME = {
        "元宝月卡", "钻石战令", "首充大礼包", "1元特惠礼包", "6元新手礼",
        "成长基金", "超值直购68", "限定皮肤", "高级月卡", "至尊战令",
        "648礼包", "328礼包", "198礼包", "周卡", "赛季通行证",
        "体力药水", "强化石礼包", "首充双倍", "传说皮肤", "荣耀战令",
    };
    // 反例两周只用整数元商品：金额分解必须落在 100 分的格子上才能凑出精确目标额
    static final int[] PLANT_GOODS = {4, 3, 5, 14, 1, 7, 2, 8, 9, 10, 13, 12, 11};
    static final int[] PLANT_WEIGHT = {8, 8, 14, 12, 26, 18, 6, 10, 6, 8, 4, 2, 1};

    static final int REFUND_ONLY_ACCT = 1300;   // 反例②：只有退款单 ⇒ 净付费 0 而非负
    static final int PLAT_CONFLICT_ACCT = 1001; // 反例③：ios 渠道却坐在 and 服
    static final int PLAT_CONFLICT_SRV = 101;
    static final String[] CHAN = {"yx", "tt", "gw", "ios"};

    // 新玩家 cohort：注册落在 2026-01-02~01-10，共 49 个。没有这个 cohort，"是新玩家还是老玩家掉的"
    // 在数据上退化成"全是老玩家"，招牌题就没有区分度。
    static final int ACCT_MAX = 1000 + ACCT_N;
    static final LocalDate COHORT_FROM = LocalDate.of(2026, 1, 1);
    // {srv_id, 上上周新玩家占该周净额比例, 上周占比}：101 由新玩家主导下跌，102 由老玩家主导
    static final double[][] NEW_SHARE = {
        {101, 0.60, 0.15},
        {102, 0.10, 0.10},
        {103, 0.20, 0.20},
        {104, 0.15, 0.10},
        {105, 0.10, 0.25},
    };

    static boolean inCohort(int acctId) {
        return acctId != REFUND_ONLY_ACCT && acctId != PLAT_CONFLICT_ACCT && (acctId - 1000) % 6 == 0;
    }

    // 只有约一半账号付过费：免费玩家占绝大多数才是真实游戏库，否则 first_pay_dt IS NULL 这个坑没有样本
    static boolean isPayer(int acctId) {
        if (acctId == REFUND_ONLY_ACCT) return false;
        return inCohort(acctId) || (acctId - 1000) % 100 < 45;
    }

    static class Order {
        String ordNo;
        int acctId, srvId, goodsId, st, isDel;
        long cents;
        LocalDateTime payTime;
    }

    static final List<Order> orders = new ArrayList<>();
    static final List<int[]> roles = new ArrayList<>();          // {roleId, acctId, srvId, roleNo}
    static final Map<Integer, List<Integer>> acctsBySrv = new LinkedHashMap<>();
    static final Map<Integer, List<Integer>> newBySrv = new LinkedHashMap<>();
    static final Map<Integer, List<Integer>> oldBySrv = new LinkedHashMap<>();
    static final Map<Long, Integer> roleNoMap = new HashMap<>();
    static final LocalDate[] regDate = new LocalDate[ACCT_MAX + 1];
    static final String[] chan = new String[ACCT_MAX + 1];
    static final int[] isDel = new int[ACCT_MAX + 1];
    static int ordSeq = 0, roleIdSeq = 0;

    static long rkey(int acctId, int srvId) { return ((long) acctId << 16) | srvId; }
    static long goodsCents(int id) { return GOODS[id - 1][1]; }
    static String goodsName(int id) { return GOODS_NAME[id - 1]; }
    static String yuan(long cents) { return String.format(Locale.ROOT, "%.2f", cents / 100.0); }
    static String signed(long cents) { return (cents < 0 ? "-" : "+") + yuan(Math.abs(cents)); }
    static String ts(LocalDateTime x) { return x.format(TS); }

    static void assertEq(String what, long expected, long actual) {
        if (expected != actual) throw new IllegalStateException(what + " 不符：期望 " + expected + "，实际 " + actual);
    }

    static LocalTime randomTime() { return LocalTime.of(8 + R.nextInt(16), R.nextInt(60), R.nextInt(60)); }

    static void addRole(int acctId, int srvId) {
        int no = roleNoMap.merge(rkey(acctId, srvId), 1, Integer::sum);
        roles.add(new int[]{++roleIdSeq, acctId, srvId, no});
    }

    static boolean hasRole(int acctId, int srvId) { return roleNoMap.containsKey(rkey(acctId, srvId)); }

    static int firstSrvOf(int acctId) {
        for (int[] r : roles) if (r[1] == acctId) return r[2];
        throw new IllegalStateException("acct" + acctId + " 没有角色");
    }

    static Order addOrder(int acctId, int srvId, int goodsId, LocalDate day, int st, int isDel) {
        Order o = new Order();
        o.ordNo = "P" + day.toString().replace("-", "") + String.format(Locale.ROOT, "%06d", ++ordSeq);
        o.acctId = acctId; o.srvId = srvId; o.goodsId = goodsId;
        o.cents = goodsCents(goodsId); o.payTime = day.atTime(randomTime()); o.st = st; o.isDel = isDel;
        orders.add(o);
        return o;
    }

    static int pickGoodsUnder(long rem) {
        int total = 0;
        for (int i = 0; i < PLANT_GOODS.length; i++) if (goodsCents(PLANT_GOODS[i]) <= rem) total += PLANT_WEIGHT[i];
        if (total == 0) return 0;
        int x = R.nextInt(total);
        for (int i = 0; i < PLANT_GOODS.length; i++) {
            long c = goodsCents(PLANT_GOODS[i]);
            if (c > rem) continue;
            x -= PLANT_WEIGHT[i];
            if (x < 0) return PLANT_GOODS[i];
        }
        return 0;
    }

    static int pickWeighted(int[] vals, int[] weights) {
        int total = 0;
        for (int w : weights) total += w;
        int x = R.nextInt(total);
        for (int i = 0; i < vals.length; i++) { x -= weights[i]; if (x < 0) return vals[i]; }
        return vals[vals.length - 1];
    }

    /** 从候选账号里挑一个"当时已经注册"的，否则订单会早于注册日 */
    static int pickAcct(List<Integer> pool, LocalDate day, int srvId) {
        for (int attempt = 0; attempt < 500; attempt++) {
            int a = pool.get(R.nextInt(pool.size()));
            if (!regDate[a].isAfter(day)) return a;
        }
        throw new IllegalStateException("srv" + srvId + " 在 " + day + " 没有已注册的账号可用");
    }

    /** 把一个子预算精确分解成 ord_st=1、is_del=0 的订单，全部落在 [mon,sun] 内 */
    static void decompose(List<Integer> pool, int srvId, long amount, LocalDate mon, LocalDate sun, long[] cnts, int wk) {
        if (amount == 0) return;
        if (amount % 100 != 0) throw new IllegalStateException("srv" + srvId + " 第" + (wk + 1) + "周子预算 " + amount + " 分不在整元格上");
        if (pool.isEmpty()) throw new IllegalStateException("srv" + srvId + " 第" + (wk + 1) + "周没有可用账号");
        int days = (int) (sun.toEpochDay() - mon.toEpochDay() + 1);
        long rem = amount;
        while (rem > 0) {
            LocalDate day = mon.plusDays(R.nextInt(days));
            int g = pickGoodsUnder(rem);
            if (g == 0) throw new IllegalStateException("srv" + srvId + " 剩余 " + rem + " 分无法分解");
            addOrder(pickAcct(pool, day, srvId), srvId, g, day, 1, 0);
            rem -= goodsCents(g);
            cnts[wk]++;
        }
    }

    static long net(int srvId, LocalDate from, LocalDate to, boolean cohortOnly) {
        long sum = 0;
        for (Order o : orders) {
            if (o.srvId != srvId || o.st != 1 || o.isDel != 0) continue;
            if (inCohort(o.acctId) != cohortOnly) continue;
            LocalDate day = o.payTime.toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            sum += o.cents;
        }
        return sum;
    }

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "src/main/resources/schema/seed_data.sql";

        // ---------- 账号 ----------
        for (int i = 1; i <= ACCT_N; i++) {
            int acctId = 1000 + i;
            if (acctId == REFUND_ONLY_ACCT) {
                regDate[acctId] = LocalDate.of(2025, 12, 5);
            } else if (inCohort(acctId)) {
                regDate[acctId] = LocalDate.of(2026, 1, 2 + R.nextInt(9));      // 01-02 ~ 01-10
            } else {
                regDate[acctId] = DATA_START.minusDays(1 + R.nextInt(240));
            }
            chan[acctId] = switch (acctId) {
                case PLAT_CONFLICT_ACCT -> "ios";
                case REFUND_ONLY_ACCT -> "gw";
                default -> CHAN[pickWeighted(new int[]{0, 1, 2, 3}, new int[]{40, 30, 20, 10})];
            };
            isDel[acctId] = acctId != REFUND_ONLY_ACCT && R.nextInt(100) < 8 ? 1 : 0;
        }

        // ---------- 角色 ----------
        for (int i = 1; i <= ACCT_N; i++) {
            int acctId = 1000 + i;
            int srvId = acctId == PLAT_CONFLICT_ACCT ? PLAT_CONFLICT_SRV
                : inCohort(acctId) ? SRV_IDS[(i / 6) % SRV_IDS.length]          // cohort 均匀铺到每个服
                : SRV_IDS[i % SRV_IDS.length];
            addRole(acctId, srvId);
        }
        int filler = 0;
        while (roles.size() < ROLE_N && filler++ < ROLE_N * 200) {
            int acctId = 1000 + 1 + R.nextInt(ACCT_N);
            if (acctId == REFUND_ONLY_ACCT) continue;
            int srvId = SRV_IDS[R.nextInt(SRV_IDS.length)];
            if (roleNoMap.getOrDefault(rkey(acctId, srvId), 0) >= 3) continue;
            addRole(acctId, srvId);
        }
        assertEq("角色总数", ROLE_N, roles.size());
        for (int s : SRV_IDS) {
            acctsBySrv.put(s, new ArrayList<>());
            newBySrv.put(s, new ArrayList<>());
            oldBySrv.put(s, new ArrayList<>());
        }
        for (int[] r : roles) {
            int srvId = r[2], acctId = r[1];
            if (!isPayer(acctId)) continue;
            List<Integer> bucket = inCohort(acctId) ? newBySrv.get(srvId) : oldBySrv.get(srvId);
            if (!bucket.contains(acctId)) bucket.add(acctId);
            if (!acctsBySrv.get(srvId).contains(acctId)) acctsBySrv.get(srvId).add(acctId);
        }
        if (!hasRole(PLAT_CONFLICT_ACCT, PLAT_CONFLICT_SRV)) throw new IllegalStateException("反例③失效");
        if (!"ios".equals(chan[PLAT_CONFLICT_ACCT])) throw new IllegalStateException("反例③渠道不符");
        for (int s : SRV_IDS) {
            if (newBySrv.get(s).isEmpty()) throw new IllegalStateException("srv" + s + " 没有新玩家，跌幅无法归因");
            if (oldBySrv.get(s).isEmpty()) throw new IllegalStateException("srv" + s + " 没有老玩家");
        }

        // ---------- 反例两周：把目标额精确分解成订单 ----------
        Map<Integer, long[]> plantedCnt = new LinkedHashMap<>();
        for (int ti = 0; ti < TARGET.length; ti++) {
            long[] t = TARGET[ti];
            int srvId = (int) t[0];
            long[] cnts = new long[2];
            for (int wk = 0; wk < 2; wk++) {
                LocalDate mon = wk == 0 ? W1_MON : W2_MON;
                LocalDate sun = wk == 0 ? W1_SUN : W2_SUN;
                long newAmt = Math.round(t[1 + wk] * NEW_SHARE[ti][1 + wk] / 100.0) * 100;
                decompose(newBySrv.get(srvId), srvId, newAmt, mon, sun, cnts, wk);
                decompose(oldBySrv.get(srvId), srvId, t[1 + wk] - newAmt, mon, sun, cnts, wk);
                assertEq("srv" + srvId + " 第" + (wk + 1) + "周净额", t[1 + wk],
                    net(srvId, mon, sun, true) + net(srvId, mon, sun, false));
            }
            plantedCnt.put(srvId, cnts);
        }

        // ---------- 反例②：只有退款单的账号 ----------
        for (int i = 0; i < 3; i++) {
            addOrder(REFUND_ONLY_ACCT, firstSrvOf(REFUND_ONLY_ACCT), 1, LocalDate.of(2026, 1, 3 + i), 2, 0);
        }

        // ---------- 其余订单：填满 ORD_TOTAL，日期一律避开两周窗口 ----------
        List<LocalDate> freeDays = new ArrayList<>();
        for (LocalDate x = DATA_START; !x.isAfter(TODAY); x = x.plusDays(1)) {
            if (x.isBefore(W1_MON) || x.isAfter(W2_SUN)) freeDays.add(x);
        }
        int guard = 0;
        while (orders.size() < ORD_TOTAL && guard++ < ORD_TOTAL * 400) {
            int acctId = 1000 + 1 + R.nextInt(ACCT_N);
            if (!isPayer(acctId)) continue;
            int srvId = SRV_IDS[R.nextInt(SRV_IDS.length)];
            if (!hasRole(acctId, srvId)) continue;
            int g = 1 + R.nextInt(GOODS.length);
            if (GOODS[g - 1][3] == 0 && R.nextInt(100) < 90) continue;
            LocalDate day = freeDays.get(R.nextInt(freeDays.size()));
            if (regDate[acctId].isAfter(day)) continue;
            addOrder(acctId, srvId, g, day, pickWeighted(new int[]{1, 2, 3}, new int[]{85, 8, 7}),
                R.nextInt(100) < 3 ? 1 : 0);
        }
        assertEq("订单总数", ORD_TOTAL, orders.size());

        // ---------- 活跃日志 ----------
        Set<String> loginKeys = new HashSet<>();
        List<String> loginRows = new ArrayList<>();
        int lg = 0;
        while (loginRows.size() < LOGIN_TOTAL && lg++ < LOGIN_TOTAL * 60) {
            int[] r = roles.get(R.nextInt(roles.size()));
            LocalDate lo = regDate[r[1]].isAfter(DATA_START) ? regDate[r[1]] : DATA_START;
            if (lo.isAfter(TODAY)) continue;
            LocalDate day = lo.plusDays(R.nextInt((int) (TODAY.toEpochDay() - lo.toEpochDay() + 1)));
            if (!loginKeys.add(r[1] + "|" + r[2] + "|" + day)) continue;
            int cnt = 1 + R.nextInt(6);
            loginRows.add("(" + r[1] + "," + r[2] + ",'" + day + "'," + cnt + "," + (cnt * (600 + R.nextInt(6600))) + ")");
        }
        assertEq("活跃日志行数", LOGIN_TOTAL, loginRows.size());

        // ---------- 道具流水 ----------
        List<String> flowRows = new ArrayList<>();
        int fg = 0;
        while (flowRows.size() < FLOW_TOTAL && fg++ < FLOW_TOTAL * 30) {
            int[] r = roles.get(R.nextInt(roles.size()));
            LocalDate lo = regDate[r[1]].isAfter(DATA_START) ? regDate[r[1]] : DATA_START;
            if (lo.isAfter(TODAY)) continue;
            LocalDate day = lo.plusDays(R.nextInt((int) (TODAY.toEpochDay() - lo.toEpochDay() + 1)));
            int type = pickWeighted(new int[]{1, 2, 3, 4}, new int[]{45, 40, 5, 10});
            int num = switch (type) {
                case 2 -> -(1 + R.nextInt(30));
                case 4 -> R.nextInt(2) == 0 ? -(1 + R.nextInt(20)) : 1 + R.nextInt(20);
                default -> 1 + R.nextInt(50);
            };
            flowRows.add("(" + flowRows.size() + "," + r[0] + "," + (1 + R.nextInt(40)) + "," + type + "," + num
                + ",'" + ts(day.atTime(randomTime())) + "')");
        }
        assertEq("道具流水行数", FLOW_TOTAL, flowRows.size());

        // ---------- 派生列 ----------
        Map<Integer, LocalDate> firstPay = new HashMap<>();
        for (Order o : orders) {
            if (o.st != 1) continue;
            LocalDate day = o.payTime.toLocalDate(), old = firstPay.get(o.acctId);
            if (old == null || day.isBefore(old)) firstPay.put(o.acctId, day);
        }
        Map<Integer, LocalDate> lastLogin = new HashMap<>();
        for (String row : loginRows) {
            int a = Integer.parseInt(row.substring(1, row.indexOf(',')));
            LocalDate day = LocalDate.parse(row.substring(row.indexOf(",'") + 2, row.indexOf(",'") + 12));
            LocalDate old = lastLogin.get(a);
            if (old == null || day.isAfter(old)) lastLogin.put(a, day);
        }

        // ---------- 输出 SQL ----------
        orders.sort((x, y) -> x.ordNo.compareTo(y.ordNo));
        StringBuilder sb = new StringBuilder(1 << 22);
        sb.append("-- 由 tools/SeedGen.java 生成（seed=").append(SEED).append("，\"今天\"=")
          .append(TODAY).append("）。勿手改：要改数据请改生成器后重跑。\n");

        List<String> rows = new ArrayList<>();
        for (int i = 0; i < SRV_IDS.length; i++) {
            rows.add("(" + SRV_IDS[i] + ",'" + SRV_NAME[i] + "','" + SRV_OPEN[i] + "',1,'" + SRV_PLAT[i] + "')");
        }
        emit(sb, "srv", "srv_id,srv_name,open_dt,srv_st,plat", rows);

        rows = new ArrayList<>();
        for (int[] g : GOODS) rows.add("(" + g[0] + ",'" + goodsName(g[0]) + "'," + yuan(g[1]) + "," + g[2] + "," + g[3] + ")");
        emit(sb, "goods", "goods_id,goods_name,price_yuan,goods_type,is_on", rows);
        int goodsN = rows.size();

        rows = new ArrayList<>();
        for (int i = 1; i <= ACCT_N; i++) {
            int acctId = 1000 + i;
            LocalDate fp = firstPay.get(acctId);
            LocalDate ll = lastLogin.getOrDefault(acctId, regDate[acctId]);
            rows.add("(" + acctId + ",'" + chan[acctId] + "','" + ts(regDate[acctId].atTime(randomTime())) + "'"
                + (fp == null ? ",NULL" : ",'" + fp + "'") + ",'" + ll + "'," + isDel[acctId] + ")");
        }
        emit(sb, "acct", "acct_id,chan_id,reg_time,first_pay_dt,last_login_dt,is_del", rows);

        rows = new ArrayList<>();
        for (int[] r : roles) {
            LocalDate lo = regDate[r[1]].isAfter(DATA_START) ? regDate[r[1]] : DATA_START;
            LocalDate c = lo.plusDays(R.nextInt((int) (TODAY.toEpochDay() - lo.toEpochDay() + 1)));
            int vip = R.nextInt(100) < 70 ? 0 : 1 + R.nextInt(10);
            rows.add("(" + r[0] + "," + r[1] + "," + r[2] + "," + r[3] + ",'" + ts(c.atTime(randomTime()))
                + "'," + (1 + R.nextInt(99)) + "," + vip + "," + isDel[r[1]] + ")");
        }
        emit(sb, "role", "role_id,acct_id,srv_id,role_no,create_time,lv,vip_lv,is_del", rows);

        emit(sb, "login_log", "acct_id,srv_id,login_dt,login_cnt,dur_sec", loginRows);

        rows = new ArrayList<>();
        for (Order o : orders) {
            rows.add("('" + o.ordNo + "'," + o.acctId + "," + o.srvId + "," + o.goodsId + "," + yuan(o.cents)
                + ",'" + ts(o.payTime) + "'," + o.st + "," + o.isDel + ")");
        }
        emit(sb, "pay_ord", "ord_no,acct_id,srv_id,goods_id,pay_amt,pay_time,ord_st,is_del", rows);

        emit(sb, "item_flow", "flow_id,role_id,item_id,chg_type,chg_num,create_time", flowRows);

        rows = new ArrayList<>();
        int sid = 0;
        for (int s : SRV_IDS) {
            rows.add("(" + (++sid) + "," + s + ",'2025-11-01','2025-12-31')");
            rows.add("(" + (++sid) + "," + s + ",'2026-01-01','2026-01-31')");
        }
        emit(sb, "season", "season_id,srv_id,st_dt,ed_dt", rows);

        // 引号必须成对：漏一个闭合引号会让整条 INSERT 语法错误，而 Java 侧完全看不出来
        long quotes = sb.chars().filter(c -> c == '\'').count();
        assertEq("SQL 里单引号总数(须为偶数)", 0, quotes % 2);

        Files.writeString(Path.of(outPath), sb.toString(), StandardCharsets.UTF_8);

        // ---------- 校验报告 ----------
        PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        out.println("== seed 校验报告  seed=" + SEED + "  今天=" + TODAY + " ==");
        out.printf(Locale.ROOT, "srv=%d goods=%d acct=%d role=%d login_log=%d pay_ord=%d item_flow=%d season=%d%n",
            SRV_IDS.length, goodsN, ACCT_N, ROLE_N, LOGIN_TOTAL, ORD_TOTAL, FLOW_TOTAL, rows.size());

        long absArg = 0, absMax = Long.MAX_VALUE;
        long pctArg = 0;
        double pctMin = Double.MAX_VALUE;
        out.println("srv   01-12~18      01-19~25      绝对额       百分比     单数(前/后)");
        for (long[] t : TARGET) {
            int srv = (int) t[0];
            long diff = t[2] - t[1];
            double pct = diff * 100.0 / t[1];
            if (diff < absMax) { absMax = diff; absArg = srv; }
            if (pct < pctMin) { pctMin = pct; pctArg = srv; }
            long[] c = plantedCnt.get(srv);
            out.printf(Locale.ROOT, "%d   %10s元   %10s元   %9s元   %+5.1f%%   %d/%d%n",
                srv, yuan(t[1]), yuan(t[2]), signed(diff), pct, c[0], c[1]);
        }
        if (pctArg == absArg) throw new IllegalStateException("反例①失效：百分比与绝对额指向同一区服");
        out.println("反例① 百分比跌幅最大=srv" + pctArg + "，绝对额跌幅最大=srv" + absArg + "  （不同 ⇒ 招牌题有区分度）");

        long bad = orders.stream().filter(o -> o.st != 1
            && !o.payTime.toLocalDate().isBefore(W1_MON) && !o.payTime.toLocalDate().isAfter(W2_SUN)).count();
        assertEq("两周窗口内非成功订单", 0, bad);
        out.println("两周窗口内无退款/失败单  OK（否则跌幅口径会含糊）");

        long roGross = orders.stream().filter(o -> o.acctId == REFUND_ONLY_ACCT).mapToLong(o -> o.cents).sum();
        long roNet = orders.stream().filter(o -> o.acctId == REFUND_ONLY_ACCT && o.st == 1).mapToLong(o -> o.cents).sum();
        if (roNet != 0) throw new IllegalStateException("反例②失效：出现成功订单");
        if (firstPay.containsKey(REFUND_ONLY_ACCT)) throw new IllegalStateException("反例②失效：first_pay_dt 不为 NULL");
        out.println("反例② acct" + REFUND_ONLY_ACCT + "：毛额 " + yuan(roGross) + " 元全是退款单 ⇒ 净付费 " + yuan(roNet)
            + " 元，first_pay_dt=NULL");

        out.println("反例③ acct" + PLAT_CONFLICT_ACCT + " chan_id=ios，角色在 srv" + PLAT_CONFLICT_SRV + "(plat=and)");

        double naive = 0;
        long exact = 0;
        for (Order o : orders) { naive += Double.parseDouble(yuan(o.cents)); exact += o.cents; }
        out.printf(Locale.ROOT, "REAL 尾差：逐行 double 相加 %.6f vs 精确 %.6f ⇒ 差 %.10f（§7 ±0.01 容差的由来）%n",
            naive, exact / 100.0, naive - exact / 100.0);

        out.println("新玩家两种口径对照（A: reg_time>='2026-01-01'；B: 注册在该周周一前 7 天内）—— 这就是必须写口径声明的原因：");
        out.println("srv   周       A新         A老         B新");
        for (long[] t : TARGET) {
            int srv = (int) t[0];
            for (int wk = 0; wk < 2; wk++) {
                LocalDate mon = wk == 0 ? W1_MON : W2_MON, sun = wk == 0 ? W1_SUN : W2_SUN;
                LocalDate cut = mon.minusDays(7);
                long aN = net(srv, mon, sun, true), aO = net(srv, mon, sun, false), bN = 0;
                for (Order o : orders) {
                    if (o.srvId != srv || o.st != 1 || o.isDel != 0) continue;
                    LocalDate day = o.payTime.toLocalDate();
                    if (day.isBefore(mon) || day.isAfter(sun)) continue;
                    if (!regDate[o.acctId].isBefore(cut)) bN += o.cents;
                }
                out.printf(Locale.ROOT, "%d  %-5s %9s元 %9s元 %9s元%n",
                    srv, wk == 0 ? "上上周" : "上周", yuan(aN), yuan(aO), yuan(bN));
            }
        }
        long dNew101 = net(101, W1_MON, W1_SUN, true) - net(101, W2_MON, W2_SUN, true);
        long dOld101 = net(101, W1_MON, W1_SUN, false) - net(101, W2_MON, W2_SUN, false);
        long dNew102 = net(102, W1_MON, W1_SUN, true) - net(102, W2_MON, W2_SUN, true);
        long dOld102 = net(102, W1_MON, W1_SUN, false) - net(102, W2_MON, W2_SUN, false);
        if (dNew101 <= dOld101) throw new IllegalStateException("反例①归因失效：srv101 跌幅不是新玩家主导");
        if (dOld102 <= dNew102) throw new IllegalStateException("反例①归因失效：srv102 跌幅不是老玩家主导");
        out.println("归因方向 srv101 新" + yuan(dNew101) + " > 老" + yuan(dOld101) + "；srv102 老" + yuan(dOld102)
            + " > 新" + yuan(dNew102) + "  OK（两问在不同区服给出不同答案，模型蒙不对）");

        long neverPaid = 0;
        for (int i = 1; i <= ACCT_N; i++) if (!firstPay.containsKey(1000 + i)) neverPaid++;
        out.println("first_pay_dt=NULL 的账号数 = " + neverPaid + "；无登录日志的账号数 = " + (ACCT_N - lastLogin.size()));

        byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hs = new StringBuilder();
        for (byte b : h) hs.append(String.format(Locale.ROOT, "%02x", b));
        out.println(outPath + "  sha256=" + hs + "  bytes=" + sb.length());
        out.flush();
    }

    static void emit(StringBuilder sb, String table, String cols, List<String> rows) {
        for (int i = 0; i < rows.size(); i += 100) {
            sb.append("INSERT INTO ").append(table).append(" (").append(cols).append(") VALUES\n");
            int end = Math.min(i + 100, rows.size());
            for (int j = i; j < end; j++) {
                sb.append(rows.get(j));
                sb.append(j == end - 1 ? ";\n" : ",\n");
            }
        }
    }
}
