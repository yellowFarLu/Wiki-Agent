package com.wikiagent.service.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 父子两级文本切分（纯逻辑组件，便于单元测试）。
 *
 * <p>算法对标业界通用的<b>递归字符切分</b>（LangChain {@code RecursiveCharacterTextSplitter}）：
 * 按分隔符优先级逐级回退 —— Markdown 标题 → 代码块围栏 → 段落（空行）→ 行 →
 * 中文句读 → 英文句点 → 空格 → 单字符兜底；每一级只在片段超长时才下沉到下一级，
 * 最终在目标长度内做合并并保留重叠窗口。父块为上下文呈现单元、子块为检索单元
 * （small-to-big / parent-document 模式）。
 *
 * <p>长度计量默认使用 {@link QwenStyleTokenEstimator}（token 口径，适配 text-embedding-v4 等
 * Qwen 系模型），可通过构造器注入其他 {@link LengthMeasurer}（例如精确 tokenizer 适配器）。
 */
public class ChunkSplitter {

    /** 长度计量器：允许把"字符数"替换为 embedding/LLM 模型的 token 口径。 */
    @FunctionalInterface
    public interface LengthMeasurer {
        int length(String text);
    }

    /**
     * 分隔符优先级（分隔符只有在文本中出现时才生效，不存在则自动降级到下一级）。
     * 标题优先保证章节完整；围栏保证代码块不被散文粘连；中文句读在前、英文句点在后，
     * 适配中英混排；空格为英文词界；空串为单字符兜底。
     *
     * <p>{@code atStart=true} 表示该分隔符属于<b>下一个</b>单元（Markdown 标题、代码围栏，
     * 切分后保留在新片段开头）；其余句读/换行分隔符终止<b>当前</b>单元，保留在片段结尾。
     */
    private record Separator(String value, boolean atStart) {
    }

    static final List<Separator> SEPARATORS = List.of(
            new Separator("\n# ", true),
            new Separator("\n## ", true),
            new Separator("\n### ", true),
            new Separator("\n#### ", true),
            new Separator("\n##### ", true),
            new Separator("\n###### ", true),
            new Separator("\n```", true),
            new Separator("\n\n", false),
            new Separator("\n", false),
            new Separator("。", false),
            new Separator("！", false),
            new Separator("？", false),
            new Separator("；", false),
            new Separator(". ", false),
            new Separator("! ", false),
            new Separator("? ", false),
            new Separator("; ", false),
            new Separator(" ", false),
            new Separator("", false));

    private final int parentSize;
    private final int parentOverlap;
    private final int childSize;
    private final int childOverlap;
    private final LengthMeasurer measurer;

    /** 兼容旧签名：使用默认的 Qwen 口径 token 估算器。参数语义已由"字符"改为"token"。 */
    public ChunkSplitter(int parentSize, int parentOverlap, int childSize, int childOverlap) {
        this(parentSize, parentOverlap, childSize, childOverlap, new QwenStyleTokenEstimator());
    }

    public ChunkSplitter(int parentSize, int parentOverlap, int childSize, int childOverlap,
                         LengthMeasurer measurer) {
        if (parentSize < 100 || childSize < 50) {
            throw new IllegalArgumentException("chunk sizes too small");
        }
        if (childOverlap >= childSize || parentOverlap >= parentSize) {
            throw new IllegalArgumentException("overlap must be smaller than chunk size");
        }
        if (measurer == null) {
            throw new IllegalArgumentException("length measurer must not be null");
        }
        this.parentSize = parentSize;
        this.parentOverlap = parentOverlap;
        this.childSize = childSize;
        this.childOverlap = childOverlap;
        this.measurer = measurer;
    }

    /** 默认长度计量器：Qwen 系 BPE 的离线估算口径（CJK 1 码元 ≈ 1 token，英文约 4 字符/token）。 */
    public static LengthMeasurer defaultLengthMeasurer() {
        return new QwenStyleTokenEstimator();
    }

    /** 一级切分：在整篇范围内递归切分出父块（目标 parentSize，带 parentOverlap 重叠）。 */
    public List<String> splitParents(String text) {
        return recursiveSplit(text, parentSize, parentOverlap);
    }

    /** 二级切分：父块未超子块目标时原样返回；否则在父块内部递归切分子块（带 childOverlap 重叠）。 */
    public List<String> splitChildren(String parentText) {
        if (measurer.length(parentText) <= childSize) {
            return List.of(parentText);
        }
        return recursiveSplit(parentText, childSize, childOverlap);
    }

    private List<String> recursiveSplit(String text, int size, int overlap) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return List.of();
        }
        if (measurer.length(t) <= size) {
            return List.of(t);
        }
        return splitBy(t, 0, size, overlap);
    }

    /** 从 separators[from] 起选择第一个在文本中出现的分隔符切分；过长片段继续下沉到下一级。 */
    private List<String> splitBy(String text, int from, int size, int overlap) {
        Separator separator = SEPARATORS.get(SEPARATORS.size() - 1);
        int sepIdx = SEPARATORS.size() - 1;
        for (int i = from; i < SEPARATORS.size(); i++) {
            Separator candidate = SEPARATORS.get(i);
            if (candidate.value().isEmpty() || text.contains(candidate.value())) {
                separator = candidate;
                sepIdx = i;
                break;
            }
        }

        List<String> goodSplits = new ArrayList<>();
        if (separator.value().isEmpty()) {
            hardSplitByMeasure(text, size, goodSplits);
            return mergeSplits(goodSplits, size, overlap);
        }

        for (String piece : splitKeepSeparator(text, separator)) {
            if (piece.isEmpty()) {
                continue;
            }
            if (measurer.length(piece) > size) {
                if (sepIdx + 1 >= SEPARATORS.size()) {
                    hardSplitByMeasure(piece, size, goodSplits);
                } else {
                    goodSplits.addAll(splitBy(piece, sepIdx + 1, size, overlap));
                }
            } else {
                goodSplits.add(piece);
            }
        }
        // 分隔符已内嵌在各片段中，合并时直接拼接、不再追加连接符（同 LangChain keep_separator 语义）
        return mergeSplits(goodSplits, size, overlap);
    }

    /**
     * 按分隔符切开并保留分隔符：{@code atStart=true} 保留在后一片段开头（标题、围栏），
     * 否则保留在当前片段结尾（句号、换行等），保证块尾落在自然边界、块首不携带悬空标点。
     */
    private List<String> splitKeepSeparator(String text, Separator separator) {
        String sep = separator.value();
        String[] raw = text.split(Pattern.quote(sep), -1);
        List<String> pieces = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            String piece;
            if (separator.atStart()) {
                piece = i == 0 ? raw[0] : sep + raw[i];
            } else {
                piece = i < raw.length - 1 ? raw[i] + sep : raw[i];
            }
            pieces.add(piece);
        }
        return pieces;
    }

    /** 在固定分隔符层级下把小片段合并成不超过目标长度的块，并从前部弹出片段保留重叠窗口。 */
    private List<String> mergeSplits(List<String> splits, int size, int overlap) {
        List<String> docs = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int total = 0;

        for (String split : splits) {
            int splitLength = measurer.length(split);
            if (!current.isEmpty() && total + splitLength > size) {
                String doc = String.join("", current).strip();
                if (!doc.isEmpty()) {
                    docs.add(doc);
                }
                // 弹出头部片段：既为当前片段腾位，也把尾部总长压到 overlap 附近
                while (!current.isEmpty()) {
                    int headLength = measurer.length(current.get(0));
                    boolean shrinkForOverlap = total - headLength >= overlap;
                    boolean shrinkForFit = total + splitLength > size;
                    if (shrinkForOverlap || shrinkForFit) {
                        current.remove(0);
                        total -= headLength;
                    } else {
                        break;
                    }
                }
            }
            current.add(split);
            total += splitLength;
        }

        String tail = String.join("", current).strip();
        if (!tail.isEmpty()) {
            docs.add(tail);
        }
        return docs;
    }

    /** 所有分隔符都失效时的最后兜底：按计量逐码元累计硬切，保证每个片段计量不超过目标长度。 */
    private void hardSplitByMeasure(String text, int size, List<String> out) {
        int start = 0;
        int currentLength = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int codePointLength = measurer.length(new String(Character.toChars(codePoint)));
            if (currentLength + codePointLength > size && i > start) {
                out.add(text.substring(start, i));
                start = i;
                currentLength = 0;
            }
            currentLength += codePointLength;
            i += charCount;
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
    }

    /**
     * Qwen 系 tokenizer 的<b>离线估算</b>（非精确 BPE），刻意偏保守以避免超出 embedding 输入限制：
     * <ul>
     *   <li>CJK 统一表意文字、假名、谚文、中文标点、全角字符：1 码元 ≈ 1 token；</li>
     *   <li>ASCII 字母数字串（按空白分隔的"词"）：约 4 字符/token，向上取整且每词至少 1 token；</li>
     *   <li>ASCII 标点：每个 ≈ 1 token；空白：0 token；</li>
     *   <li>其他码元（emoji 等）：保守计 1 token/码元。</li>
     * </ul>
     * 需要精确口径时可注入基于模型 tokenizer 的 {@link LengthMeasurer} 实现替换。
     */
    static final class QwenStyleTokenEstimator implements LengthMeasurer {

        @Override
        public int length(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            int tokens = 0;
            int i = 0;
            while (i < text.length()) {
                int codePoint = text.codePointAt(i);
                int charCount = Character.charCount(codePoint);

                if (isCjk(codePoint)) {
                    tokens++;
                } else if (!Character.isWhitespace(codePoint)) {
                    if (codePoint < 128) {
                        int alphanumeric = 0;
                        int punctuation = 0;
                        int j = i;
                        while (j < text.length()) {
                            int cp = text.codePointAt(j);
                            if (isCjk(cp) || Character.isWhitespace(cp)) {
                                break;
                            }
                            if (cp < 128) {
                                if (Character.isLetterOrDigit(cp)) {
                                    alphanumeric++;
                                } else {
                                    punctuation++;
                                }
                                j++;
                            } else {
                                break;
                            }
                        }
                        if (alphanumeric > 0) {
                            tokens += Math.max(1, (alphanumeric + 3) / 4);
                        }
                        tokens += punctuation;
                        i = j;
                        continue;
                    }
                    tokens++;
                }
                i += charCount;
            }
            return tokens;
        }

        private static boolean isCjk(int codePoint) {
            return (codePoint >= 0x3000 && codePoint <= 0x303F)       // CJK 符号和中文标点
                    || (codePoint >= 0x3040 && codePoint <= 0x30FF)  // 平假名/片假名
                    || (codePoint >= 0x3400 && codePoint <= 0x4DBF)  // 扩展 A
                    || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)  // CJK 统一表意文字
                    || (codePoint >= 0xF900 && codePoint <= 0xFAFF)  // 兼容表意文字
                    || (codePoint >= 0xFF00 && codePoint <= 0xFFEF)  // 全角 ASCII/符号
                    || (codePoint >= 0x20000 && codePoint <= 0x2FA1F); // 扩展 B-F
        }
    }
}
