package com.fongmi.android.tv.player.media;

import androidx.media3.common.util.UriUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M3U8 广告净化工具 —— 完整移植自 TVBox (com.github.tvbox.osc.util.M3U8)
 * 原理: 播放器解析 m3u8 之前, 先剔除广告分片(少数派域名/广告分段/时长匹配), 播放时丝滑无广告。
 * 相比 media3 内置 adblock(播放中跳过会卡), 本方案是"播放前净化", 不卡顿。
 */
public class M3U8 {

    private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String TAG_MEDIA_DURATION = "#EXTINF";
    private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
    private static final String TAG_KEY = "#EXT-X-KEY";
    /** 统计用: 与 m3u8 同目录的分片归一标记 */
    private static final String SAME_DIR = "://same-dir/";

    private static final Pattern REGEX_X_DISCONTINUITY = Pattern.compile("#EXT-X-DISCONTINUITY[\\s\\S]*?(?=#EXT-X-DISCONTINUITY|$)");
    private static final Pattern REGEX_MEDIA_DURATION = Pattern.compile(TAG_MEDIA_DURATION + ":([\\d\\.]+)\\b");
    private static final Pattern REGEX_URI = Pattern.compile("URI=\"(.+?)\"");
    public static int currentAdCount;

    public static boolean isAd(String regex) {
        return regex.contains(TAG_DISCONTINUITY) || regex.contains(TAG_MEDIA_DURATION) || regex.contains(TAG_ENDLIST) || regex.contains(TAG_KEY) || M3U8.isDouble(regex);
    }

    public static String purify(String tsUrlPre, String m3u8content) {
        currentAdCount = 0;
        if (null == m3u8content || m3u8content.length() == 0) return null;
        if (!m3u8content.startsWith("#EXTM3U")) return null;
        String result = removeMinorityUrl(tsUrlPre, m3u8content);
        if (result == null) result = get(tsUrlPre, m3u8content);
        if (result != null) return absolutize(tsUrlPre, result);
        return null;
    }

    /** 把净化后 m3u8 里的相对路径分片/KEY 转为绝对 URL (基于 tsUrlPre 目录), 供本地代理播放 */
    private static String absolutize(String base, String m3u8) {
        StringBuilder sb = new StringBuilder();
        for (String line : m3u8.split("\n")) {
            String resolved = line;
            if (line.startsWith(TAG_KEY)) {
                Matcher matcher = REGEX_URI.matcher(line);
                String value = matcher.find() ? matcher.group(1) : null;
                if (value != null && !value.startsWith("http://") && !value.startsWith("https://")) {
                    resolved = line.replace(value, resolvePath(base, value));
                }
            } else if (!line.startsWith("#") && !line.startsWith("http://") && !line.startsWith("https://")) {
                resolved = resolvePath(base, line);
            }
            sb.append(resolved).append("\n");
        }
        return sb.toString();
    }

    private static String resolvePath(String base, String path) {
        if (path.startsWith("/")) {
            int idx = base.indexOf('/', 9);
            return idx > 0 ? base.substring(0, idx) + path : path;
        }
        return base + path;
    }

    private static double maxPercent(HashMap<String, Integer> preUrlMap) {
        int maxTimes = 0, totalTimes = 0;
        for (Map.Entry<String, Integer> entry : preUrlMap.entrySet()) {
            if (entry.getValue() > maxTimes) maxTimes = entry.getValue();
            totalTimes += entry.getValue();
        }
        return maxTimes * 1.0 / (totalTimes * 1.0);
    }

    private static int timesNoAd = 15; // 出现超过多少次的域名不认为是广告

    private static String removeMinorityUrl(String tsUrlPre, String m3u8content) {
        // 旧版3.5.7同款算法: 按 #EXT-X-DISCONTINUITY 分段, 段总时长显著短于平均的段视为广告整段删除
        // (广告段通常很短; 删除总量限制防误删; 段级删除保证播放器时间轴连续不卡广告时长)
        String linesplit = "\n";
        if (m3u8content.contains("\r\n")) linesplit = "\r\n";
        String[] lines = m3u8content.split(linesplit);
        boolean hasDiscontinuity = false;
        for (String line : lines) if (line.startsWith(TAG_DISCONTINUITY)) { hasDiscontinuity = true; break; }
        if (!hasDiscontinuity) return null; // 无 DISCONTINUITY 分段标记, 不做段级判断

        // 分段: 每段累加 EXTINF 时长, 记录每段前是否有 DISCONTINUITY 标记
        List<BigDecimal> durations = new ArrayList<>();
        List<String> segments = new ArrayList<>();
        List<Boolean> segHasDis = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        BigDecimal currentDuration = BigDecimal.ZERO;
        boolean hasDis = false;
        for (String line : lines) {
            if (line.startsWith(TAG_DISCONTINUITY)) {
                // 记录: 当前正在累积的段(若有)之前有标记; 下一段前也有标记
                if (current.length() > 0) {
                    segments.add(current.toString());
                    durations.add(currentDuration);
                    segHasDis.add(hasDis);
                    current = new StringBuilder();
                    currentDuration = BigDecimal.ZERO;
                }
                hasDis = true; // 下一段前有 DISCONTINUITY
                continue;
            }
            if (current.length() == 0 && segments.isEmpty() && !hasDis) {
                // 首段无标记
            }
            current.append(line).append(linesplit);
            if (line.startsWith(TAG_MEDIA_DURATION)) {
                Matcher m = REGEX_MEDIA_DURATION.matcher(line);
                if (m.find()) {
                    try { currentDuration = currentDuration.add(new BigDecimal(m.group(1))); } catch (Exception ignored) {}
                }
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString());
            durations.add(currentDuration);
            segHasDis.add(hasDis);
        }
        if (segments.size() < 2) return null;

        // 分片总数 <= 100 不净化 (旧版3.5.7同款门槛, 防误删短视频)
        int segCount = 0;
        for (String seg : segments) for (String l : seg.split(linesplit)) if (!l.startsWith("#") && !l.trim().isEmpty()) segCount++;
        if (segCount <= 100) return null;

        // 平均段时长
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal d : durations) total = total.add(d);
        BigDecimal avg = total.divide(BigDecimal.valueOf(segments.size()), 2, java.math.RoundingMode.HALF_UP);

        // 判定广告段: 段时长 < 60s 且 平均 > 段时长*2 且 段时长 < 平均
        // 或 段内分片 URL 与主流编辑距离过大 (旧版3.5.7同款, 识别同目录不同文件名的广告)
        int removedSeconds = 0;
        boolean[] delSegment = new boolean[segments.size()];
        String mainUrl = firstUrl(segments.get(0));
        for (int i = 0; i < segments.size(); i++) {
            BigDecimal d = durations.get(i);
            int sec = d.intValue();
            boolean shortAd = sec < 60 && avg.intValue() > sec * 2 && d.compareTo(avg) < 0;
            boolean urlDiff = false;
            String segUrl = firstUrl(segments.get(i));
            if (mainUrl != null && segUrl != null) {
                int dist = levenshtein(mainUrl, segUrl);
                urlDiff = dist > mainUrl.length() / 2; // 编辑距离超过主流URL一半视为不同来源
            }
            if (shortAd || urlDiff) {
                delSegment[i] = true;
                removedSeconds += sec;
            }
        }
        // 删除总量 > 180s 放弃 (防误删)
        if (removedSeconds == 0 || removedSeconds > 180) return null;
        currentAdCount += removedSeconds;

        // 重组: 跳过广告段, 按原样恢复 DISCONTINUITY 标记 (media3 用它分配独立时间戳调整器平滑PTS跳变)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (delSegment[i]) continue;
            if (segHasDis.get(i)) sb.append(TAG_DISCONTINUITY).append(linesplit);
            sb.append(segments.get(i));
        }
        return sb.toString();
    }


    private static String get(String tsUrlPre, String m3u8Content) {
        m3u8Content = m3u8Content.replaceAll("\r\n", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : m3u8Content.split("\n")) sb.append(shouldResolve(line) ? resolve(tsUrlPre, line) : line).append("\n");
        List<String> ads = getRegex(tsUrlPre);
        if (ads == null || ads.isEmpty()) return null;
        return clean(sb.toString(), ads);
    }

    private static List<String> getRegex(String tsUrlPre) {
        // 无解析规则表时返回 null, 走 removeMinorityUrl 主路径
        return null;
    }

    private static String clean(String line, List<String> ads) {
        boolean scan = false;
        for (String ad : ads) {
            if (ad.contains(TAG_DISCONTINUITY) || ad.contains(TAG_MEDIA_DURATION)) line = scanAd(line, ad);
            else if (isDouble(ad)) scan = true;
        }
        return scan ? scan(line, ads) : line;
    }

    private static String scanAd(String line, String TAG_AD) {
        Matcher m1 = getPattern(TAG_AD).matcher(line);
        List<String> needRemoveAd = new ArrayList<>();
        while (m1.find()) {
            String group = m1.group();
            String groupCleaned = group.replace(TAG_ENDLIST, "");
            Matcher m2 = REGEX_MEDIA_DURATION.matcher(group);
            int tCount = 0;
            while (m2.find()) tCount += 1;
            needRemoveAd.add(groupCleaned);
            currentAdCount += tCount;
        }
        for (String rem : needRemoveAd) line = line.replace(rem, "");
        return line;
    }

    private static String scan(String line, List<String> ads) {
        Matcher m1 = REGEX_X_DISCONTINUITY.matcher(line);
        List<String> needRemoveAd = new ArrayList<>();
        while (m1.find()) {
            String group = m1.group();
            String groupCleaned = group.replace(TAG_ENDLIST, "");
            Matcher m2 = REGEX_MEDIA_DURATION.matcher(group);
            BigDecimal ft = BigDecimal.ZERO, lt = BigDecimal.ZERO, t = BigDecimal.ZERO;
            int tCount = 0;
            while (m2.find()) {
                if (ft.equals(BigDecimal.ZERO)) ft = new BigDecimal(m2.group(1));
                lt = new BigDecimal(m2.group(1));
                t = t.add(lt);
                tCount += 1;
            }
            String ftStr = ft.toString(), ltStr = lt.toString(), tStr = t.toString();
            for (String ad : ads) {
                if (ad.startsWith("-")) {
                    String adClean = ad.substring(1);
                    // 匹配最后一条切片
                    if (ltStr.startsWith(adClean)) {
                        needRemoveAd.add(groupCleaned);
                        currentAdCount += tCount;
                        break;
                    }
                } else {
                    // 匹配第一条切片或广告切片总时长
                    if (ftStr.startsWith(ad) || tStr.startsWith(ad)) {
                        needRemoveAd.add(groupCleaned);
                        currentAdCount += tCount;
                        break;
                    }
                }
            }
        }
        for (String rem : needRemoveAd) line = line.replace(rem, "");
        return line;
    }

    private static boolean isDouble(String ad) {
        try {
            return Double.parseDouble(ad) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 取段内第一个分片 URL (绝对或相对) */
    private static String firstUrl(String segment) {
        for (String line : segment.split("\n")) {
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            return line;
        }
        return null;
    }

    /** Levenshtein 编辑距离 (旧版3.5.7 d2/m.e 同款) */
    private static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    /** 提取分片 URL 的父路径: 绝对 URL 取 域名+目录; 无斜杠相对路径(同目录)返回 null; 以/开头的返回其目录 */
    private static String parentPath(String line) {
        if (line.startsWith("http://") || line.startsWith("https://")) {
            int last = line.lastIndexOf('/');
            if (last <= 9) return null;
            return line.substring(0, last + 1);
        }
        if (line.startsWith("/")) {
            int last = line.lastIndexOf('/');
            if (last > 1) return line.substring(0, last + 1);
        }
        return null; // 同目录分片
    }

    private static boolean shouldResolve(String line) {
        return (!line.startsWith("#") && !line.startsWith("http")) || line.startsWith(TAG_KEY);
    }

    private static String resolve(String base, String line) {
        if (line.startsWith(TAG_KEY)) {
            Matcher matcher = REGEX_URI.matcher(line);
            String value = matcher.find() ? matcher.group(1) : null;
            return value == null ? line : line.replace(value, UriUtil.resolve(base, value));
        } else {
            return UriUtil.resolve(base, line);
        }
    }

    private static Pattern getPattern(String regex) {
        return Pattern.compile(regex);
    }
}
