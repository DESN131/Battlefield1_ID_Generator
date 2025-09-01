import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 高性能命令行版 EAID 计算器
 * - 多线程
 * - 无 Swing/UI
 * - 位运算加速（字符集 64）
 * - 批量计数与批量输出
 *
 * 用法：
 *   java MainFast "<EAID 模式>" "<目标Hash(十六进制)或0>" [--threads=N] [--name-file=path/to/name.txt]
 *
 * 示例：
 *   java MainFast "Satori_@@@@@@@" "7D543A64"
 *   java MainFast "@@_Koishi_@@" "0" --threads=16 --name-file=./name.txt
 */
public class MainFast {

    // 字符集（长度64，便于位运算）
    private static final char[] CHARSET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_".toCharArray();
    private static final int CHARSET_LEN = CHARSET.length; // 64

    // 权重（最多22位）
    private static final long[] WEIGHT = {
            0x00000001L, 0x00000021L, 0x00000441L, 0x00008C61L,
            0x00121881L, 0x025528A1L, 0x4CFA3CC1L, 0xEC41D4E1L,
            0x747C7101L, 0x040A9121L, 0x855CB541L, 0x30F35D61L,
            0x4F5F0981L, 0x3B4039A1L, 0xA3476DC1L, 0x0C3525E1L,
            0x92D9E201L, 0xEE162221L, 0xB0DA6641L, 0xCC272E61L,
            0x510CFA81L, 0x72AC4AA1L,
    };

    // private static final long MOD32 = 0x1_0000_0000L; // 2^32

    // 读取的中文名映射（hash32 -> 中文名）
    private static final Map<Integer, String> TEXT_MAP = new ConcurrentHashMap<>();

    // 计数器
    private static final LongAdder ATTEMPTS = new LongAdder();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：java MainFast \"<EAID 模式>\" \"<目标Hash(十六进制)或0>\" [--threads=N] [--name-file=path/to/name.txt]");
            System.err.println("示例：java MainFast \"Satori_@@@@@@@\" \"7D543A64\"");
            System.exit(1);
        }

        final String pattern = args[0].trim();
        final String hashHex = args[1].trim();

        int threads = Runtime.getRuntime().availableProcessors();
        String nameFilePath = null;

        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--threads=")) {
                threads = Math.max(1, Integer.parseInt(a.substring("--threads=".length())));
            } else if (a.startsWith("--name-file=")) {
                nameFilePath = a.substring("--name-file=".length());
            }
        }

        if (pattern.length() > 16) {
            System.err.println("EAID 过长（最多 16 位）");
            System.exit(2);
        }

        long targetHash;
        try {
            targetHash = Long.parseUnsignedLong(hashHex, 16);
        } catch (Exception e) {
            System.err.println("目标 Hash 解析失败，请输入十六进制（或 0）： " + e.getMessage());
            System.exit(3);
            return;
        }

        // 读中文映射 name.txt（只有在 targetHash==0 时才真正需要）
        loadLocalization(nameFilePath);

        // 计算
        runSearch(pattern, targetHash, threads);
    }

    /** 主搜索逻辑 */
    private static void runSearch(String pattern, long targetHash, int threads) throws InterruptedException {
        final String newPattern = pattern.replace('@', '!');
        final int size = newPattern.length();

        // 基础 hash：把 '!' 当作占位，直接参与 encode
        final long baseHash = encode(newPattern);

        // 收集通配位置 & 对应的权重下标
        final List<Integer> wildcardPos = new ArrayList<>();
        final List<Integer> weightIndexList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (newPattern.charAt(i) == '!') {
                wildcardPos.add(i);
                weightIndexList.add(size - i - 1);
            }
        }
        final int wc = wildcardPos.size();

        // 64^wc 的总组合数，wc>=11 会超过 Long.MAX_VALUE（2^63-1）
        if (wc >= 11) {
            System.out.println("通配符数量 = " + wc + "，搜索空间 64^" + wc + " 过大，超出 long 范围。请减少通配符。");
            return;
        }
        final long total = pow64(wc);

        System.out.printf("模式: %s | 通配位: %d | 总组合: %,d | 线程: %d%n", pattern, wc, total, threads);

        // 预计算：每个通配位的权重、字符差值表
        final long[] wArr = new long[wc];
        for (int i = 0; i < wc; i++) {
            wArr[i] = WEIGHT[weightIndexList.get(i)];
        }
        final int[] posArr = new int[wc];
        for (int i = 0; i < wc; i++) {
            posArr[i] = wildcardPos.get(i);
        }
        final int[] charsetDelta = new int[CHARSET_LEN];
        for (int i = 0; i < CHARSET_LEN; i++) {
            charsetDelta[i] = CHARSET[i] - '!';
        }

        final boolean matchAny = (targetHash == 0);
        final long tStart = System.nanoTime();
        ATTEMPTS.reset();

        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch latch = new CountDownLatch(threads);

        // 进度线程：每秒输出一次
        final AtomicBoolean running = new AtomicBoolean(true);
        Thread progress = new Thread(() -> {
            long last = 0;
            long lastTs = System.nanoTime();
            while (running.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                long done = ATTEMPTS.sum();
                long now = System.nanoTime();
                double dt = (now - lastTs) / 1e9;
                double instRate = (done - last) / Math.max(1e-6, dt);
                double pct = total == 0 ? 100.0 : (done * 100.0 / total);
                double elapsed = (now - tStart) / 1e9;
                double eta = (instRate > 0) ? (total - done) / instRate : Double.POSITIVE_INFINITY;
                System.out.printf("[进度] %,d / %,d (%.2f%%) | 速度: %.0f/s | 经过: %.1fs | 预计剩余: %.1fs%n",
                        done, total, pct, instRate, elapsed, Double.isInfinite(eta) ? 0.0 : eta);
                last = done; lastTs = now;
            }
        }, "progress");
        progress.setDaemon(true);
        progress.start();

        // 分块（均分到线程）
        long chunk = Math.max(1, total / threads);
        for (int t = 0; t < threads; t++) {
            final long start = t * chunk;
            final long end = (t == threads - 1) ? total : Math.min(total, start + chunk);

            pool.submit(() -> {
                try {
                    if (start >= end) return;

                    // 本地计数、命中缓冲（批量输出）
                    long local = 0;
                    StringBuilder hitBuf = new StringBuilder(4096);

                    // 遍历 [start, end)
                    for (long i = start; i < end; i++) {
                        long idx = i;
                        long extra = 0L;

                        // 逐通配位选择字符（里程表展开），用位运算替代除/模
                        for (int j = 0; j < wc; j++) {
                            int cidx = (int) (idx & 63L); // 等价于 %64
                            idx >>>= 6;                    // 等价于 /64（无符号）

                            // 累加 hash 贡献
                            extra += (wArr[j] * (long) charsetDelta[cidx]) & 0xFFFFFFFFL;
                            extra &= 0xFFFFFFFFL;
                        }

                        long totalHash = (baseHash + (extra & 0xFFFFFFFFL)) & 0xFFFFFFFFL;

                        if (matchAny) {
                            // 任意匹配：如果存在中文名则输出
                            String zh = TEXT_MAP.get((int) totalHash);
                            if (zh != null) {
                                // 命中时才重建字符串（避免每次都构造）
                                String id = reconstruct(newPattern, posArr, i);
                                hitBuf.append("匹配: ").append(id)
                                      .append(" -> ").append(zh)
                                      .append(" (").append(String.format("%08X", (int) totalHash)).append(")\n");
                                if (hitBuf.length() > 2048) {
                                    synchronized (System.out) { System.out.print(hitBuf); }
                                    hitBuf.setLength(0);
                                }
                            }
                        } else {
                            if (totalHash == targetHash) {
                                String zh = TEXT_MAP.getOrDefault((int) targetHash, "未知");
                                String id = reconstruct(newPattern, posArr, i);
                                hitBuf.append("命中: ").append(id)
                                      .append(" -> ").append(zh)
                                      .append(" (").append(String.format("%08X", (int) totalHash)).append(")\n");
                                if (hitBuf.length() > 1024) {
                                    synchronized (System.out) { System.out.print(hitBuf); }
                                    hitBuf.setLength(0);
                                }
                            }
                        }

                        // 批量合并计数，降低 LongAdder 热点
                        local++;
                        if ((local & 0x3FFF) == 0) { // 每 16384 次合并一次
                            ATTEMPTS.add(local);
                            local = 0;
                        }
                    }

                    if (local > 0) ATTEMPTS.add(local);
                    if (hitBuf.length() > 0) {
                        synchronized (System.out) { System.out.print(hitBuf); }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        running.set(false);
        progress.join(200); // 给进度线程一点收尾时间
        pool.shutdown();

        long tEnd = System.nanoTime();
        long done = ATTEMPTS.sum();
        double seconds = (tEnd - tStart) / 1e9;
        double rate = done / Math.max(1e-9, seconds);
        System.out.printf("完成：%,d / %,d | 总耗时：%.3fs | 平均速度：%.0f/s%n", done, total, seconds, rate);
    }

    /** 仅在命中时把索引 i 反解为具体字符串（把 '!' 位替换为对应字符） */
    private static String reconstruct(String newPattern, int[] posArr, long index) {
        char[] out = newPattern.toCharArray();
        long idx = index;
        for (int j = 0; j < posArr.length; j++) {
            int cidx = (int) (idx & 63L);
            idx >>>= 6;
            out[posArr[j]] = CHARSET[cidx];
        }
        return new String(out);
    }

    /** 原始 encode：与 GUI 版保持一致 */
    public static long encode(String input) {
        long result = 0;
        for (int i = 0; i < input.length(); i++) {
            int value = input.charAt(i) - 32;
            result = ((result * 33) & 0xFFFFFFFFL) + value;
        }
        return (result - 1) & 0xFFFFFFFFL;
    }

    /** 读取 name.txt：支持类路径 / 也可通过 --name-file 指定路径 */
    private static void loadLocalization(String nameFilePath) {
        InputStream in = null;
        try {
            if (nameFilePath != null) {
                in = new FileInputStream(nameFilePath);
            } else {
                // 从 classpath 读取 /name.txt
                in = MainFast.class.getResourceAsStream("/name.txt");
                if (in == null) {
                    System.out.println("未找到 /name.txt（类路径）。如需任意匹配（hash=0），请提供 --name-file=path");
                    return;
                }
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                Pattern p = Pattern.compile("^(\\p{XDigit}+),\"(.*)\"$");
                String line; int n=0;
                while ((line = reader.readLine()) != null) {
                    Matcher m = p.matcher(line);
                    if (m.matches()) {
                        int key = Integer.parseUnsignedInt(m.group(1), 16);
                        String value = m.group(2);
                        TEXT_MAP.put(key, value);
                        n++;
                    }
                }
                if (n > 0) System.out.println("已加载中文名映射条目数: " + n);
            }
        } catch (IOException e) {
            System.out.println("读取 name.txt 失败：" + e.getMessage());
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    /** 64 的幂（wc < 11 时安全） */
    private static long pow64(int wc) {
        long v = 1L;
        for (int i = 0; i < wc; i++) v <<= 6; // *64
        return v;
    }
}
