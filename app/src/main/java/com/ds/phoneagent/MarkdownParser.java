package com.ds.phoneagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small bounded Markdown subset. No HTML parser, network access or executable content. */
final class MarkdownParser {
    static final int MAX_INPUT = 48000;
    private static final int MAX_BLOCKS = 160, MAX_ROWS = 24, MAX_COLUMNS = 8;
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*)|$)");
    private static final Pattern LIST = Pattern.compile("^( {0,12})([-+*]|[0-9]{1,9}[.)])[ \\t]+(.*)$");
    private static final Pattern SEPARATOR = Pattern.compile(":?-{3,}:?");

    static final class Document {
        final List<Block> blocks = new ArrayList<>();
        boolean truncated;
    }
    static final class Block {
        final String kind;
        String text = "", marker = "";
        int level, indent;
        List<List<String>> rows = Collections.emptyList();
        List<Integer> alignments = Collections.emptyList();
        Block(String kind, String text) { this.kind = kind; this.text = text; }
    }
    static final class Inline {
        final StringBuilder text = new StringBuilder();
        final List<Span> spans = new ArrayList<>();
    }
    static final class Span {
        final String kind, value;
        final int start, end;
        Span(String kind, String value, int start, int end) {
            this.kind = kind; this.value = value; this.start = start; this.end = end;
        }
    }
    private static final class Budget {
        int remaining;
        Budget(int size) { remaining = Math.min(400000, size * 8 + 1024); }
        boolean step() { return --remaining >= 0; }
    }

    static String bounded(String input) {
        if (input == null) return "";
        int end = Math.min(input.length(), MAX_INPUT);
        if (end < input.length() && end > 0 && Character.isHighSurrogate(input.charAt(end - 1))) end--;
        return input.substring(0, end).replace("\r\n", "\n").replace('\r', '\n');
    }

    static Document parse(String input) {
        Document doc = new Document();
        doc.truncated = input != null && input.length() > MAX_INPUT;
        String source = bounded(input);
        String[] lines = source.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            if (lines[i].trim().isEmpty()) { i++; continue; }
            if (doc.blocks.size() >= MAX_BLOCKS - 1) {
                // Retain remaining content as one selectable plain block, without more parsing.
                doc.blocks.add(new Block("plain", join(lines, i, lines.length)));
                break;
            }
            String line = lines[i];
            String fence = fence(line);
            if (fence != null) {
                int start = ++i;
                while (i < lines.length && !closesFence(lines[i], fence)) i++;
                Block block = new Block("code", join(lines, start, i));
                doc.blocks.add(block);
                if (i < lines.length) i++;
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                Block block = new Block("heading", heading.group(2) == null ? "" : headingText(heading.group(2)));
                block.level = heading.group(1).length();
                doc.blocks.add(block); i++; continue;
            }
            if (i + 1 < lines.length && !line.trim().isEmpty() && setext(lines[i + 1]) > 0) {
                Block block = new Block("heading", line.trim());
                block.level = setext(lines[i + 1]);
                doc.blocks.add(block); i += 2; continue;
            }
            if (rule(line)) { doc.blocks.add(new Block("rule", "")); i++; continue; }
            if (quote(line) != null) {
                StringBuilder text = new StringBuilder();
                while (i < lines.length && quote(lines[i]) != null) {
                    if (text.length() > 0) text.append('\n');
                    text.append(quote(lines[i++]));
                }
                doc.blocks.add(new Block("quote", text.toString())); continue;
            }
            Matcher list = LIST.matcher(line);
            if (list.matches()) {
                Block block = new Block("list", list.group(3));
                block.indent = Math.min(4, list.group(1).length() / 2);
                block.marker = list.group(2).length() == 1 ? "•" : list.group(2);
                int contentIndent = list.group(1).length() + list.group(2).length() + 1;
                i++;
                StringBuilder text = new StringBuilder(block.text);
                while (i < lines.length && !lines[i].trim().isEmpty()
                        && leadingSpaces(lines[i]) >= contentIndent && !LIST.matcher(lines[i]).matches()
                        && fence(lines[i]) == null) {
                    text.append('\n').append(lines[i++].substring(contentIndent));
                }
                block.text = text.toString();
                if (block.text.startsWith("[ ] ")) { block.marker = "☐"; block.text = block.text.substring(4); }
                else if (block.text.startsWith("[x] ") || block.text.startsWith("[X] ")) { block.marker = "☑"; block.text = block.text.substring(4); }
                doc.blocks.add(block); continue;
            }
            if (line.startsWith("    ") || line.startsWith("\t")) {
                StringBuilder code = new StringBuilder();
                while (i < lines.length && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isEmpty())) {
                    if (code.length() > 0) code.append('\n');
                    String value = lines[i++];
                    code.append(value.isEmpty() ? "" : value.substring(value.startsWith("\t") ? 1 : 4));
                }
                doc.blocks.add(new Block("code", code.toString())); continue;
            }
            List<String> header = cells(line);
            List<Integer> align = i + 1 < lines.length ? tableAlignment(lines[i + 1]) : null;
            if (hasPipe(line) && align != null && header.size() == align.size() && header.size() <= MAX_COLUMNS) {
                int start = i;
                List<List<String>> rows = new ArrayList<>();
                rows.add(header); i += 2;
                while (i < lines.length && !lines[i].trim().isEmpty() && hasPipe(lines[i]) && rows.size() < MAX_ROWS) {
                    List<String> row = cells(lines[i]);
                    if (row.size() > header.size()) break;
                    while (row.size() < header.size()) row.add("");
                    rows.add(row); i++;
                }
                Block block = new Block("table", join(lines, start, i));
                block.rows = rows; block.alignments = align;
                doc.blocks.add(block); continue;
            }
            int start = i++;
            while (i < lines.length && !lines[i].trim().isEmpty() && !startsBlock(lines, i)) i++;
            doc.blocks.add(new Block("paragraph", join(lines, start, i)));
        }
        return doc;
    }

    private static boolean startsBlock(String[] lines, int i) {
        String line = lines[i];
        return fence(line) != null || HEADING.matcher(line).matches() || rule(line)
                || quote(line) != null || LIST.matcher(line).matches()
                || line.startsWith("    ") || line.startsWith("\t")
                || (i + 1 < lines.length && (setext(lines[i + 1]) > 0
                    || (hasPipe(line) && tableAlignment(lines[i + 1]) != null)));
    }
    private static String join(String[] lines, int start, int end) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < end; i++) { if (i > start) out.append('\n'); out.append(lines[i]); }
        return out.toString();
    }
    private static int leadingSpaces(String line) {
        int i = 0; while (i < line.length() && line.charAt(i) == ' ') i++; return i;
    }
    private static String headingText(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) end--;
        int hash = end;
        while (hash > 0 && value.charAt(hash - 1) == '#') hash--;
        if (hash < end && (hash == 0 || value.charAt(hash - 1) == ' ' || value.charAt(hash - 1) == '\t')) {
            while (hash > 0 && (value.charAt(hash - 1) == ' ' || value.charAt(hash - 1) == '\t')) hash--;
            return value.substring(0, hash);
        }
        return value.substring(0, end);
    }
    private static String fence(String line) {
        int start = leadingSpaces(line);
        if (start > 3 || start >= line.length()) return null;
        char c = line.charAt(start);
        if (c != '`' && c != '~') return null;
        int end = start; while (end < line.length() && line.charAt(end) == c) end++;
        return end - start >= 3 ? line.substring(start, end) : null;
    }
    private static boolean closesFence(String line, String opening) {
        String closing = fence(line);
        return closing != null && closing.charAt(0) == opening.charAt(0) && closing.length() >= opening.length()
                && line.trim().substring(closing.length()).trim().isEmpty();
    }
    private static int setext(String line) {
        if (leadingSpaces(line) > 3) return 0;
        String s = line.trim();
        if (s.matches("={3,}")) return 1;
        return s.matches("-{3,}") ? 2 : 0;
    }
    private static boolean rule(String line) {
        if (leadingSpaces(line) > 3) return false;
        String compact = line.replace(" ", "").replace("\t", "");
        return compact.matches("\\*{3,}|-{3,}|_{3,}");
    }
    private static String quote(String line) {
        int i = leadingSpaces(line);
        if (i > 3 || i >= line.length() || line.charAt(i) != '>') return null;
        i++; if (i < line.length() && line.charAt(i) == ' ') i++;
        return line.substring(i);
    }
    private static boolean hasPipe(String line) { return cells(line).size() > 1 || line.trim().startsWith("|"); }
    private static List<String> cells(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        int codeRun = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) { cell.append(c).append(line.charAt(++i)); continue; }
            if (c == '`') {
                int end = i; while (end < line.length() && line.charAt(end) == '`') end++;
                int run = end - i;
                if (codeRun == 0) codeRun = run; else if (codeRun == run) codeRun = 0;
                cell.append(line, i, end); i = end - 1; continue;
            }
            if (c == '|' && codeRun == 0) { result.add(cell.toString().trim()); cell.setLength(0); }
            else cell.append(c);
        }
        result.add(cell.toString().trim());
        if (result.size() > 1 && result.get(0).isEmpty() && line.trim().startsWith("|")) result.remove(0);
        if (result.size() > 1 && result.get(result.size() - 1).isEmpty() && line.trim().endsWith("|")) result.remove(result.size() - 1);
        return result;
    }
    private static List<Integer> tableAlignment(String line) {
        List<String> cells = cells(line);
        if (cells.isEmpty() || cells.size() > MAX_COLUMNS) return null;
        List<Integer> align = new ArrayList<>();
        for (String cell : cells) {
            if (!SEPARATOR.matcher(cell).matches()) return null;
            align.add(cell.startsWith(":") && cell.endsWith(":") ? 0 : cell.endsWith(":") ? 1 : -1);
        }
        return align;
    }

    static Inline inline(String source) {
        String text = bounded(source);
        Inline out = new Inline();
        inline(text, 0, text.length(), 0, true, out, new Budget(text.length()));
        return out;
    }
    private static void span(Inline out, String kind, String value, int start) {
        if (out.text.length() > start && out.spans.size() < 768)
            out.spans.add(new Span(kind, value, start, out.text.length()));
    }
    private static void inline(String s, int begin, int end, int depth, boolean links, Inline out, Budget budget) {
        if (depth >= 8 || out.spans.size() >= 768) { out.text.append(s, begin, end); return; }
        int i = begin;
        while (i < end) {
            if (!budget.step() || out.spans.size() >= 768) { out.text.append(s, i, end); return; }
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < end && (punctuation(s.charAt(i + 1)) || s.charAt(i + 1) == '\n')) {
                out.text.append(s.charAt(i + 1)); i += 2; continue;
            }
            if (c == '`') {
                int run = run(s, i, end, c), close = codeEnd(s, i + run, end, run, budget);
                if (close >= 0) {
                    String value = s.substring(i + run, close).replace('\n', ' ');
                    if (value.startsWith(" ") && value.endsWith(" ") && !value.trim().isEmpty()) value = value.substring(1, value.length() - 1);
                    int start = out.text.length(); out.text.append(value); span(out, "code", "", start);
                    i = close + run; continue;
                }
                out.text.append(s, i, i + run); i += run; continue;
            }
            boolean image = c == '!' && i + 1 < end && s.charAt(i + 1) == '[';
            if ((c == '[' || image) && links) {
                int label = i + (image ? 2 : 1), close = balanced(s, label, end, '[', ']', budget);
                if (close >= 0 && close + 1 < end && s.charAt(close + 1) == '(') {
                    int finish = balanced(s, close + 2, end, '(', ')', budget);
                    if (finish >= 0) {
                        String target = destination(s.substring(close + 2, finish));
                        if (target != null) {
                            if (image) out.text.append("[图片：");
                            int start = out.text.length(); inline(s, label, close, depth + 1, false, out, budget);
                            if (image) out.text.append(']'); else span(out, "link", target, start);
                            i = finish + 1; continue;
                        }
                    }
                }
            }
            if (c == '<' && links) {
                int finish = i + 1;
                while (finish < end && s.charAt(finish) != '>' && budget.step()) finish++;
                if (finish < end && s.charAt(finish) == '>') {
                    String target = s.substring(i + 1, finish);
                    if (target.length() <= 2048 && webPrefix(target)) {
                        int start = out.text.length(); out.text.append(target); span(out, "link", target, start);
                        i = finish + 1; continue;
                    }
                    // Preserve HTML tags literally, including URLs in their attributes.
                    out.text.append(s, i, finish + 1); i = finish + 1; continue;
                }
            }
            if (links && (s.startsWith("https://", i) || s.startsWith("http://", i))
                    && (i == begin || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                int finish = i;
                while (finish < end && finish - i < 2048 && !Character.isWhitespace(s.charAt(finish))
                        && "<>\"'".indexOf(s.charAt(finish)) < 0 && budget.step()) finish++;
                while (finish > i && ".,;:!?，。；：！？、）】".indexOf(s.charAt(finish - 1)) >= 0) finish--;
                String target = s.substring(i, finish);
                int extraClosing = count(target, ')') - count(target, '(');
                while (finish > i && s.charAt(finish - 1) == ')' && extraClosing-- > 0) finish--;
                target = s.substring(i, finish);
                if (finish > i) {
                    int start = out.text.length(); out.text.append(target); span(out, "link", target, start);
                    i = finish; continue;
                }
            }
            if (c == '*' || c == '_' || (c == '~' && i + 1 < end && s.charAt(i + 1) == '~')) {
                int amount = run(s, i, end, c);
                if (amount > (c == '~' ? 2 : 3)) {
                    out.text.append(s, i, i + amount); i += amount; continue;
                }
                boolean wordUnderscore = c == '_' && i > begin && Character.isLetterOrDigit(s.charAt(i - 1));
                if (!wordUnderscore && i + amount < end && !Character.isWhitespace(s.charAt(i + amount))) {
                    int close = emphasisEnd(s, i + amount, end, c, amount, budget);
                    if (close >= 0) {
                        int start = out.text.length(); inline(s, i + amount, close, depth + 1, links, out, budget);
                        if (c == '~') span(out, "strike", "", start);
                        else { if (amount >= 2) span(out, "bold", "", start); if (amount != 2) span(out, "italic", "", start); }
                        i = close + amount; continue;
                    }
                }
            }
            out.text.append(c); i++;
        }
    }
    private static int run(String s, int i, int end, char c) { int start = i; while (i < end && s.charAt(i) == c) i++; return i - start; }
    private static int codeEnd(String s, int i, int end, int amount, Budget budget) {
        while (i < end && budget.step()) {
            if (s.charAt(i) == '`') { int n = run(s, i, end, '`'); if (n == amount) return i; i += n; }
            else i++;
        }
        return -1;
    }
    private static int emphasisEnd(String s, int begin, int end, char c, int amount, Budget budget) {
        int nested = 0;
        for (int i = begin; i < end && budget.step(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '`') {
                int n = run(s, i, end, '`'), close = codeEnd(s, i + n, end, n, budget);
                if (close >= 0) { i = close + n - 1; continue; }
            }
            if (s.charAt(i) != c) continue;
            int n = run(s, i, end, c);
            boolean close = i > begin && !Character.isWhitespace(s.charAt(i - 1));
            if (c == '_' && i + n < end && Character.isLetterOrDigit(s.charAt(i + n))) close = false;
            if (nested > 0 && close && n >= nested) {
                int consumed = nested; nested = 0;
                if (n - consumed == amount) return i + consumed;
            } else if (nested == 0 && n == amount && close) return i;
            else if (n != amount && n <= 3 && i + n < end && !Character.isWhitespace(s.charAt(i + n))) nested = n;
            i += n - 1;
        }
        return -1;
    }
    private static int balanced(String s, int begin, int end, char open, char close, Budget budget) {
        int nesting = 0;
        for (int i = begin; i < end && budget.step(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == open) { if (++nesting > 8) return -1; }
            if (c == close) { if (nesting == 0) return i; nesting--; }
        }
        return -1;
    }
    private static String destination(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.startsWith("<")) {
            int end = value.indexOf('>'); if (end < 0) return null; value = value.substring(1, end);
        } else {
            for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i))) { value = value.substring(0, i); break; }
        }
        return value.replaceAll("\\\\([!\"#$%&'()*+,\\-./:;<=>?@\\[\\]\\\\^_`{|}~])", "$1");
    }
    private static boolean webPrefix(String value) { return value.startsWith("https://") || value.startsWith("http://"); }
    private static boolean punctuation(char c) { return c < 128 && !Character.isLetterOrDigit(c) && !Character.isWhitespace(c); }
    private static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }
}
