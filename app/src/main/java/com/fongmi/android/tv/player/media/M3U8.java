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
        if (result != null) return result;
        result = get(tsUrlPre, m3u8content);
        return result;
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
        String linesplit = "\n";
        if (m3u8content.contains("\r\n")) linesplit = "\r\n";
        String[] lines = m3u8content.split(linesplit);

        // 阶段1: 按"父路径"统计各分片所在路径出现次数 (广告分片通常在不同目录/域名)
        // 用父路径而不是文件名截断, 避免纯数字序号分片(0000000.ts...)被误判为不同前缀
        HashMap<String, Integer> preUrlMap = new HashMap<>();
        for (String line : lines) {
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            String preUrl = parentPath(line);
            String key = preUrl == null ? SAME_DIR : preUrl;
            Integer cnt = preUrlMap.get(key);
            preUrlMap.put(key, cnt != null ? cnt + 1 : 1);
        }
        if (preUrlMap.size() <= 1) return null;
        boolean domainFiltering = false;
        if (maxPercent(preUrlMap) < 0.8) {
            // 域名统计: 取同域名最多的链接, 其它域名当作广告
            preUrlMap.clear();
            for (String line : lines) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                if (!line.startsWith("http://") && !line.startsWith("https://")) return null;
                int ifirst = line.indexOf('/', 9);
                if (ifirst <= 0) continue;
                String preUrl = line.substring(0, ifirst);
                Integer cnt = preUrlMap.get(preUrl);
                preUrlMap.put(preUrl, cnt != null ? cnt + 1 : 1);
            }
            if (preUrlMap.size() <= 1) return null;
            if (maxPercent(preUrlMap) < 0.8) return null; // 正常分片占比不够大
            boolean allDomainsExceedThreshold = true;
            for (Integer count : preUrlMap.values()) {
                if (count <= 15) {
                    allDomainsExceedThreshold = false;
                    break;
                }
            }
            if (allDomainsExceedThreshold) return null;
            domainFiltering = true;
        }

        // 找出出现次数最多的 key (父路径或域名)
        int maxTimes = 0;
        String maxTimesPreUrl = "";
        for (Map.Entry<String, Integer> entry : preUrlMap.entrySet()) {
            if (entry.getValue() > maxTimes) {
                maxTimesPreUrl = entry.getKey();
                maxTimes = entry.getValue();
            }
        }
        if (maxTimes == 0) return null;

        // 阶段2: 标记广告分片 (父路径/域名与主流不一致, 且不在 SAME_DIR)
        boolean[] ad = new boolean[lines.length];
        for (int i = 0; i < lines.length; ++i) {
            String line = lines[i];
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            String path = parentPath(line);
            String key = path == null ? SAME_DIR : path;
            if (key.equals(SAME_DIR) || key.equals(maxTimesPreUrl)) continue; // 正常分片
            ad[i] = true; // 广告分片
            currentAdCount += 1;
        }
        int adCount = 0;
        for (boolean b : ad) if (b) adCount++;
        if (adCount == 0) return null;

        // 阶段3: 段级删除 —— 按 #EXT-X-DISCONTINUITY 分段, 广告分片所在的整段删除
        // 这样播放器按序播放不会遇到分片序列空洞 (旧版3.5.7同款思路: 多移除一点, 保证无缝)
        boolean hasDiscontinuity = m3u8content.contains(TAG_DISCONTINUITY);
        if (hasDiscontinuity) {
            // 段级删除: 按 #EXT-X-DISCONTINUITY 分段, 段内含广告分片则整段删除
            // (包括段首 DISCONTINUITY 标记和段内所有 EXTINF/分片, 保证不产生孤儿标记破坏配对)
            boolean[] delLine = new boolean[lines.length];
            boolean inAdSegment = false;
            int segmentStart = -1;
            List<Integer> segmentLines = new ArrayList<>();
            for (int i = 0; i < lines.length; ++i) {
                String line = lines[i];
                if (line.startsWith(TAG_DISCONTINUITY)) {
                    // 上一段结束: 若含广告则整段标记删除
                    if (inAdSegment) for (int idx : segmentLines) delLine[idx] = true;
                    inAdSegment = false;
                    segmentStart = i;
                    segmentLines = new ArrayList<>();
                    segmentLines.add(i);
                    continue;
                }
                segmentLines.add(i);
                if (ad[i]) inAdSegment = true;
            }
            // 最后一段
            if (inAdSegment) for (int idx : segmentLines) delLine[idx] = true;
            return rebuildLines(lines, delLine, linesplit);
        } else {
            // 无 DISCONTINUITY: 逐行删除广告分片, 同时清掉其前一行 (#EXTINF), 避免留空
            boolean[] delLine = new boolean[lines.length];
            for (int i = 0; i < lines.length; ++i) {
                if (ad[i]) {
                    delLine[i] = true;
                    if (i > 0) delLine[i - 1] = true;
                }
            }
            return rebuildLines(lines, delLine, linesplit);
        }
    }

    /** 按删除标记重建 m3u8 (跳过被删行, 保留 #EXT-X-ENDLIST) */
    private static String rebuildLines(String[] lines, boolean[] delLine, String linesplit) {
        StringBuilder sb = new StringBuilder();
        boolean lastDeleted = true;
        for (int i = 0; i < lines.length; ++i) {
            if (delLine[i]) {
                lastDeleted = true;
                continue;
            }
            if (lines[i].length() == 0) {
                if (!lastDeleted) sb.append(linesplit);
                continue;
            }
            sb.append(lines[i]).append(linesplit);
            lastDeleted = false;
        }
        String result = sb.toString();
        // 去掉末尾多余换行, 保证以 ENDLIST 结尾
        while (result.endsWith("\n\n")) result = result.substring(0, result.length() - 1);
        return result;
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
