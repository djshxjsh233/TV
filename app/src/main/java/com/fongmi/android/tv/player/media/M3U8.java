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
 * M3U8 广告净化工具 —— 完整移植自 TVBoxOS (q215613905/TVBoxOS) 最新维护版
 * 作者: asdfgh, FongMi
 * 原理: 播放器解析 m3u8 之前剔除广告分片, 播放时丝滑无广告。
 * 核心:
 * 1. removeMinorityUrl: URL 前缀/域名统计, 删除少数派(广告)
 * 2. cleanCommonAdMarkers: 识别 CUE-OUT/CUE-IN、SCTE35 等广告标记, 删除广告区间
 * 3. cleanDiscontinuityGroups: 按 DISCONTINUITY 分组, 整组删除广告组(含标记), 保证播放器时间轴连续无缝
 * 4. 广告特征: URL 关键词正则 + 广告域名黑名单
 * 5. 安全校验: 删除比例>30%放弃 / >50%回退原内容 / isPlayableMediaPlaylist 校验
 */
public class M3U8 {
    private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String TAG_MEDIA_DURATION = "#EXTINF";
    private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
    private static final String TAG_KEY = "#EXT-X-KEY";
    private static final String TAG_MAP = "#EXT-X-MAP";
    private static final String TAG_CUE_OUT = "#EXT-X-CUE-OUT";
    private static final String TAG_CUE_IN = "#EXT-X-CUE-IN";
    private static final String TAG_DATERANGE = "#EXT-X-DATERANGE";

    private static final Pattern REGEX_X_DISCONTINUITY = Pattern.compile("#EXT-X-DISCONTINUITY[\\s\\S]*?(?=#EXT-X-DISCONTINUITY|$)");
    private static final Pattern REGEX_MEDIA_DURATION = Pattern.compile(TAG_MEDIA_DURATION + ":([\\d\\.]+)\\b");
    private static final Pattern REGEX_URI = Pattern.compile("URI=\"(.+?)\"");

    /** 广告片段 URL 特征识别 */
    private static final Pattern REGEX_AD_SEGMENT_URI = Pattern.compile("(?i)(^|[/?&=_.-])(ads?|adv|advert(ise(ment)?)?|commercial|preroll|pre-roll|midroll|mid-roll|postroll|post-roll|sponsor|scte|vast|vmap|interstitial|bumper)([/?&=_.-]|$)");

    /** 广告域名特征 (常见广告CDN) */
    private static final String[] AD_DOMAIN_KEYWORDS = {
            "adservice", "adserver", "adsystem", "doubleclick", "googlesyndication",
            "advertising", "2mdn.net", "moatads", "scorecardresearch", "quantserve"
    };

    public static int currentAdCount;

    public static boolean isAd(String regex) {
        return regex.contains(TAG_DISCONTINUITY) || regex.contains(TAG_MEDIA_DURATION) || regex.contains(TAG_ENDLIST) || regex.contains(TAG_KEY) || regex.contains(TAG_CUE_OUT) || regex.contains(TAG_CUE_IN) || regex.contains(TAG_DATERANGE) || M3U8.isDouble(regex);
    }

    public static String purify(String tsUrlPre, String m3u8content) {
        long start = System.currentTimeMillis();
        currentAdCount = 0;
        if (null == m3u8content || m3u8content.length() == 0) return null;
        if (m3u8content.startsWith("\ufeff")) m3u8content = m3u8content.substring(1);
        if (!m3u8content.startsWith("#EXTM3U")) return null;

        int totalSegments = 0;
        String[] lines = m3u8content.split(m3u8content.contains("\r\n") ? "\r\n" : "\n");
        for (String line : lines) {
            if (line.length() > 0 && line.charAt(0) != '#') totalSegments++;
        }

        String result = removeMinorityUrl(tsUrlPre, m3u8content);
        if (result != null && currentAdCount > 0) result = get(tsUrlPre, result);
        else result = get(tsUrlPre, m3u8content);
        result = keepVodEndList(m3u8content, result);

        // 安全校验: 删除过多则回退原内容
        if (totalSegments > 0 && currentAdCount > totalSegments * 0.5) {
            currentAdCount = 0;
            result = m3u8content;
        }
        if (currentAdCount > 0 && !isPlayableMediaPlaylist(result)) {
            currentAdCount = 0;
            result = m3u8content;
        }
        android.util.Log.i("M3u8Ad", "净化耗时: " + (System.currentTimeMillis() - start) + "ms, 移除: " + currentAdCount + " 分片");
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

    private static int timesNoAd = 15;

    private static String removeMinorityUrl(String tsUrlPre, String m3u8content) {
        String linesplit = "\n";
        if (m3u8content.contains("\r\n")) linesplit = "\r\n";
        String[] lines = m3u8content.split(linesplit);

        int totalSegments = 0;
        for (String line : lines) {
            if (line.length() > 0 && line.charAt(0) != '#') totalSegments++;
        }

        // First pass: count normalized media path prefixes.
        HashMap<String, Integer> preUrlMap = new HashMap<>();
        for (String line : lines) {
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            String absoluteUrl = toAbsoluteUrl(tsUrlPre, line);
            int ilast = absoluteUrl.lastIndexOf('.');
            if (ilast <= 4) continue;
            String preUrl = absoluteUrl.substring(0, ilast - 4);
            Integer cnt = preUrlMap.get(preUrl);
            preUrlMap.put(preUrl, cnt != null ? cnt + 1 : 1);
        }
        if (preUrlMap.size() <= 1) return null;
        boolean domainFiltering = false;
        if (maxPercent(preUrlMap) < 0.8) {
            // Fallback to dominant host filtering.
            preUrlMap.clear();
            for (String line : lines) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                String absoluteUrl = toAbsoluteUrl(tsUrlPre, line);
                if (!absoluteUrl.startsWith("http://") && !absoluteUrl.startsWith("https://")) return null;
                int ifirst = absoluteUrl.indexOf('/', 9);
                if (ifirst <= 0) continue;
                String preUrl = absoluteUrl.substring(0, ifirst);
                Integer cnt = preUrlMap.get(preUrl);
                preUrlMap.put(preUrl, cnt != null ? cnt + 1 : 1);
            }
            if (preUrlMap.size() <= 1) return null;
            if (maxPercent(preUrlMap) < 0.8) return null;
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

        // Keep the most common media prefix or host.
        int maxTimes = 0;
        String maxTimesPreUrl = "";
        for (Map.Entry<String, Integer> entry : preUrlMap.entrySet()) {
            if (entry.getValue() > maxTimes) {
                maxTimesPreUrl = entry.getKey();
                maxTimes = entry.getValue();
            }
        }
        if (maxTimes == 0) return null;

        StringBuilder filtered = new StringBuilder();
        List<String> pendingSegmentTags = new ArrayList<>();
        for (int i = 0; i < lines.length; ++i) {
            String item = lines[i].trim();
            if (item.length() == 0) {
                if (pendingSegmentTags.isEmpty()) appendLine(filtered, lines[i], linesplit);
                else pendingSegmentTags.add(lines[i]);
                continue;
            }
            if (item.charAt(0) == '#') {
                String output = hasUriAttribute(item) ? resolveUriLine(tsUrlPre, lines[i]) : lines[i];
                if (isSegmentTag(item)) pendingSegmentTags.add(output);
                else {
                    flush(filtered, pendingSegmentTags, linesplit);
                    appendLine(filtered, output, linesplit);
                }
                continue;
            }

            String absoluteUrl = toAbsoluteUrl(tsUrlPre, lines[i]);
            if (shouldKeepMediaUrl(absoluteUrl, domainFiltering, maxTimesPreUrl, preUrlMap)) {
                flush(filtered, pendingSegmentTags, linesplit);
                appendLine(filtered, absoluteUrl, linesplit);
            } else {
                pendingSegmentTags.clear();
                currentAdCount += 1;
            }
        }

        // Safety check: if removal ratio is too high, likely a false positive
        if (totalSegments > 0 && currentAdCount > totalSegments * 0.3) {
            currentAdCount = 0;
            return null;
        }

        return normalizeMediaPlaylist(filtered.toString());
    }

    private static String get(String tsUrlPre, String m3u8Content) {
        String line = resolveContent(tsUrlPre, m3u8Content);
        line = cleanCommonAdMarkers(tsUrlPre, line);
        return cleanDiscontinuityGroups(tsUrlPre, line);
    }

    private static String resolveContent(String tsUrlPre, String m3u8Content) {
        m3u8Content = m3u8Content.replaceAll("\r\n", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : m3u8Content.split("\n")) {
            sb.append(shouldResolve(line) ? resolve(tsUrlPre, line.trim()) : line).append("\n");
        }
        return sb.toString();
    }

    private static String cleanCommonAdMarkers(String tsUrlPre, String m3u8Content) {
        String line = resolveContent(tsUrlPre, m3u8Content);
        StringBuilder sb = new StringBuilder();
        List<String> pending = new ArrayList<>();
        boolean inAdBreak = false;
        boolean changed = false;

        for (String raw : line.split("\n", -1)) {
            String item = raw.trim();
            if (item.length() == 0) {
                if (pending.isEmpty()) sb.append(raw).append("\n");
                else pending.add(raw);
                continue;
            }
            if (item.startsWith("#")) {
                if (item.startsWith(TAG_CUE_IN)) {
                    if (inAdBreak || hasAdSignal(pending)) {
                        inAdBreak = false;
                        pending.clear();
                        changed = true;
                        continue;
                    }
                }
                if (isAdBreakStart(item)) {
                    flush(sb, pending);
                    inAdBreak = true;
                    pending.add(raw);
                    changed = true;
                    continue;
                }
                if (inAdBreak) {
                    pending.add(raw);
                    changed = true;
                    continue;
                }
                if (isStandaloneAdTag(item)) {
                    flush(sb, pending);
                    currentAdCount += 1;
                    changed = true;
                    continue;
                }
                if (isSegmentTag(item) || isAdSignalTag(item)) {
                    pending.add(raw);
                } else {
                    flush(sb, pending);
                    sb.append(raw).append("\n");
                }
                continue;
            }

            // URL 特征和域名特征
            if (inAdBreak || hasAdSignal(pending) || isAdSegmentUri(item) || hasAdDomain(item)) {
                pending.clear();
                currentAdCount += 1;
                changed = true;
                continue;
            }
            flush(sb, pending);
            sb.append(raw).append("\n");
        }

        if (!inAdBreak) flush(sb, pending);
        return changed ? sb.toString() : line;
    }

    private static void flush(StringBuilder sb, List<String> pending) {
        for (String line : pending) sb.append(line).append("\n");
        pending.clear();
    }

    private static void flush(StringBuilder sb, List<String> pending, String linesplit) {
        for (String line : pending) appendLine(sb, line, linesplit);
        pending.clear();
    }

    private static void appendLine(StringBuilder sb, String line, String linesplit) {
        sb.append(line).append(linesplit);
    }

    private static boolean hasAdSignal(List<String> pending) {
        for (String line : pending) {
            if (isAdBreakStart(line.trim()) || isAdSignalTag(line.trim())) return true;
        }
        return false;
    }

    private static boolean isAdBreakStart(String line) {
        return line.startsWith(TAG_CUE_OUT);
    }

    private static boolean isAdSignalTag(String line) {
        if (line.startsWith("#EXT-OATCLS-SCTE35")) return true;
        if (line.startsWith("#EXT-X-SCTE35")) return true;
        if (line.startsWith("#EXT-X-SPLICEPOINT-SCTE35")) return true;
        if (line.startsWith("#EXT-X-CUE")) return true;
        if (line.startsWith("#EXT-X-ASSET")) return true;
        if (line.startsWith("#EXT-X-VMAP-AD-BREAK")) return true;
        if (line.startsWith("#EXT-X-AD")) return true;
        return false;
    }

    private static boolean isSegmentTag(String line) {
        if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) return false;
        return line.startsWith(TAG_MEDIA_DURATION) || line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME") || line.startsWith("#EXT-X-DISCONTINUITY") || line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT");
    }

    private static boolean isStandaloneAdTag(String line) {
        if (!line.startsWith(TAG_DATERANGE)) return false;
        return isAdLikeText(line) || line.contains("X-ASSET-URI") || line.contains("X-ASSET-LIST");
    }

    private static boolean isAdLikeText(String line) {
        String lower = line.toLowerCase();
        return lower.contains("scte") || lower.contains("cue") || lower.contains("interstitial") ||
                lower.contains("vmap") || lower.contains("vast") || lower.contains("advert") ||
                lower.contains("commercial") || lower.contains("ad-") || lower.contains("ad_") ||
                lower.contains("ad.") || lower.contains("preroll") || lower.contains("midroll") ||
                lower.contains("postroll") || lower.contains("bumper");
    }

    private static boolean isAdSegmentUri(String line) {
        return REGEX_AD_SEGMENT_URI.matcher(line).find();
    }

    private static boolean hasAdDomain(String url) {
        String lower = url.toLowerCase();
        for (String keyword : AD_DOMAIN_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    /** 按 DISCONTINUITY 分组, 整组删除广告组 (含标记) —— 保证播放器时间轴连续无缝 */
    private static String cleanDiscontinuityGroups(String tsUrlPre, String m3u8Content) {
        String line = resolveContent(tsUrlPre, m3u8Content);
        String[] lines = line.split("\n");
        List<Group> groups = buildDiscontinuityGroups(lines);
        if (groups.size() < 3) return line;
        Group main = findMainGroup(groups);
        if (main == null || main.segmentCount < 3) return line;

        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (Group group : groups) {
            if (shouldDropGroup(group, main)) {
                currentAdCount += group.segmentCount;
                changed = true;
                continue;
            }
            group.appendTo(sb);
        }
        // 净化后移除所有 DISCONTINUITY 标记 (旧版3.5.7同款): 播放器把剩余分片当连续流,
        // 用默认时间戳调整器顺序推进, 彻底无缝不卡顿 (保留标记会导致 media3 切换调整器触发重置)
        String result = sb.toString();
        if (changed) result = result.replaceAll("(?m)^#EXT-X-DISCONTINUITY\\s*\\n?", "");
        return changed ? result : line;
    }

    private static List<Group> buildDiscontinuityGroups(String[] lines) {
        List<Group> groups = new ArrayList<>();
        Group group = new Group();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith(TAG_DISCONTINUITY) && group.hasMedia()) {
                groups.add(group);
                group = new Group();
            }
            group.add(raw);
        }
        if (group.hasMedia() || !group.lines.isEmpty()) groups.add(group);
        return groups;
    }

    private static Group findMainGroup(List<Group> groups) {
        Group main = null;
        for (Group group : groups) {
            if (group.segmentCount == 0) continue;
            if (main == null || group.score() > main.score()) main = group;
        }
        return main;
    }

    private static boolean shouldDropGroup(Group group, Group main) {
        if (group == main || group.segmentCount == 0) return false;

        boolean shortGroup = group.segmentCount <= 2 ||
                (main.totalDuration > 0 && group.totalDuration > 0 &&
                        group.totalDuration < main.totalDuration * 0.18);

        boolean differentHost = main.host.length() > 0 && group.host.length() > 0 &&
                !main.host.equals(group.host);

        boolean differentPath = main.pathPrefix.length() > 0 && group.pathPrefix.length() > 0 &&
                !main.pathPrefix.equals(group.pathPrefix);

        boolean hasAdFeature = group.adLikeCount > 0 || hasAdDomain(group.host) ||
                isAdSegmentUri(group.pathPrefix);

        // 增强: 短组(时长显著小于主组)且路径不同 → 广告 (不限分片数, 兼容多片广告段如bfzy adjump)
        boolean shortDifferentPath = shortGroup && differentPath;

        boolean adLike = hasAdFeature || differentHost || shortDifferentPath;

        return shortGroup && adLike;
    }

    private static String hostOf(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "";
        int start = url.indexOf("://") + 3;
        int end = url.indexOf('/', start);
        return end > start ? url.substring(start, end) : url.substring(start);
    }

    private static String pathPrefixOf(String url) {
        String clean = url;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int slash = clean.lastIndexOf('/');
        return slash > 0 ? clean.substring(0, slash + 1) : "";
    }

    private static class Group {
        private final List<String> lines = new ArrayList<>();
        private int segmentCount = 0;
        private int adLikeCount = 0;
        private double totalDuration = 0;
        private String host = "";
        private String pathPrefix = "";

        private void add(String raw) {
            lines.add(raw);
            String line = raw.trim();
            Matcher matcher = REGEX_MEDIA_DURATION.matcher(line);
            if (matcher.find()) {
                try {
                    totalDuration += Double.parseDouble(matcher.group(1));
                } catch (Exception ignored) {
                }
            }
            if (line.length() == 0 || line.startsWith("#")) {
                if (isAdSignalTag(line) || isStandaloneAdTag(line)) adLikeCount += 1;
                return;
            }
            segmentCount += 1;
            if (isAdSegmentUri(line) || hasAdDomain(line)) adLikeCount += 1;
            if (host.length() == 0) host = hostOf(line);
            if (pathPrefix.length() == 0) pathPrefix = pathPrefixOf(line);
        }

        private boolean hasMedia() {
            return segmentCount > 0;
        }

        private void appendTo(StringBuilder sb) {
            for (String line : lines) sb.append(line).append("\n");
        }

        private double score() {
            return totalDuration > 0 ? totalDuration : segmentCount;
        }
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
                    if (ltStr.startsWith(adClean)) {
                        needRemoveAd.add(groupCleaned);
                        currentAdCount += tCount;
                        break;
                    }
                } else {
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

    private static boolean shouldResolve(String line) {
        String item = line.trim();
        if (item.length() == 0) return false;
        return (!item.startsWith("#") && !item.startsWith("http")) || hasUriAttribute(item);
    }

    private static String resolve(String base, String line) {
        if (hasUriAttribute(line)) {
            return resolveUriLine(base, line);
        } else {
            return UriUtil.resolve(base, line);
        }
    }

    private static boolean hasUriAttribute(String line) {
        return line.contains("URI=\"") && (line.contains(TAG_KEY) || line.contains(TAG_MAP));
    }

    private static String resolveUriLine(String base, String line) {
        Matcher matcher = REGEX_URI.matcher(line);
        if (!matcher.find()) return line;
        String value = matcher.group(1);
        if (value == null) return line;
        String resolved = value.startsWith("http://") || value.startsWith("https://") ? value : UriUtil.resolve(base, value);
        return line.replace("URI=\"" + value + "\"", "URI=\"" + resolved + "\"");
    }

    private static String normalizeMediaPlaylist(String content) {
        StringBuilder sb = new StringBuilder();
        boolean seenMedia = false;
        boolean hasPendingDiscontinuity = false;
        String pendingDiscontinuity = "";
        for (String raw : content.replaceAll("\r\n", "\n").split("\n", -1)) {
            String item = raw.trim();
            if (isDiscontinuityTag(item)) {
                if (seenMedia && !hasPendingDiscontinuity) {
                    pendingDiscontinuity = raw;
                    hasPendingDiscontinuity = true;
                }
                continue;
            }
            if (hasPendingDiscontinuity) {
                if (item.length() == 0) continue;
                if (!item.startsWith(TAG_ENDLIST)) sb.append(pendingDiscontinuity).append("\n");
                hasPendingDiscontinuity = false;
            }
            if (item.length() == 0 && sb.length() == 0) continue;
            sb.append(raw).append("\n");
            if (isMediaUriLine(item)) seenMedia = true;
        }
        return sb.toString();
    }

    private static boolean isPlayableMediaPlaylist(String content) {
        if (content == null || !content.startsWith("#EXTM3U")) return false;
        int mediaCount = 0;
        boolean pendingExtInf = false;
        for (String raw : content.replaceAll("\r\n", "\n").split("\n")) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith(TAG_MEDIA_DURATION)) {
                if (pendingExtInf) return false;
                pendingExtInf = true;
            } else if (isMediaUriLine(line)) {
                mediaCount += 1;
                pendingExtInf = false;
            } else if (line.startsWith(TAG_ENDLIST) && pendingExtInf) {
                return false;
            }
        }
        return mediaCount > 0 && !pendingExtInf;
    }

    private static String keepVodEndList(String original, String result) {
        if (result == null) return null;
        if (!hasEndList(original) || hasEndList(result)) return result;
        return result + (result.endsWith("\n") ? "" : "\n") + TAG_ENDLIST + "\n";
    }

    private static boolean hasEndList(String content) {
        if (content == null) return false;
        for (String raw : content.replaceAll("\r\n", "\n").split("\n")) {
            if (raw.trim().startsWith(TAG_ENDLIST)) return true;
        }
        return false;
    }

    private static boolean isMediaUriLine(String line) {
        return line.length() > 0 && !line.startsWith("#");
    }

    private static boolean isDiscontinuityTag(String line) {
        return line.startsWith(TAG_DISCONTINUITY) && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE");
    }

    private static String toAbsoluteUrl(String base, String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("/")) {
            int idx = base.indexOf('/', 9);
            return idx > 0 ? base.substring(0, idx) + url : url;
        }
        return base + url;
    }

    private static boolean shouldKeepMediaUrl(String absoluteUrl, boolean domainFiltering, String maxTimesPreUrl, HashMap<String, Integer> preUrlMap) {
        if (!domainFiltering) {
            if (absoluteUrl.startsWith(maxTimesPreUrl)) return true;
            int ilast = absoluteUrl.lastIndexOf('.');
            if (ilast <= 4) return false;
            String preUrl = absoluteUrl.substring(0, ilast - 4);
            Integer cnt = preUrlMap.get(preUrl);
            return cnt != null && cnt > 1;
        } else {
            int ifirst = absoluteUrl.indexOf('/', 9);
            String domain = ifirst > 0 ? absoluteUrl.substring(0, ifirst) : absoluteUrl;
            Integer cnt = preUrlMap.get(domain);
            return domain.equals(maxTimesPreUrl) || (cnt != null && cnt > timesNoAd);
        }
    }

    private static Pattern getPattern(String regex) {
        return Pattern.compile(regex);
    }
}
