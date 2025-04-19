package com.cfin.novel.cfinmybatislog.manager;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.components.Service;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;

@Service(Service.Level.PROJECT)
public final class MyBatisLogManager {
    private static final Logger LOG = Logger.getInstance(MyBatisLogManager.class);

    // 定义更丰富的颜色模式 - 使用现代UI设计风格的色彩
    // SQL关键字颜色 - 蓝色系
    private static final JBColor KEYWORD_COLOR = new JBColor(
            new Color(0, 91, 187),     // 亮色模式：皇家蓝
            new Color(86, 156, 214)    // 暗色模式：活力蓝
    );

    // 表名颜色 - 橙色系
    private static final JBColor TABLE_COLOR = new JBColor(
            new Color(209, 105, 0),    // 亮色模式：深橙色
            new Color(215, 186, 125)   // 暗色模式：金褐色
    );

    // 操作符颜色 - 灰色系
    private static final JBColor OPERATOR_COLOR = new JBColor(
            new Color(85, 85, 85),     // 亮色模式：深灰色
            new Color(180, 180, 180)   // 暗色模式：浅灰色
    );

    // 函数颜色 - 红色系
    private static final JBColor FUNCTION_COLOR = new JBColor(
            new Color(175, 0, 0),      // 亮色模式：深红色
            new Color(220, 120, 120)   // 暗色模式：浅红色
    );

    // 数值颜色 - 绿色系
    private static final JBColor NUMBER_COLOR = new JBColor(
            new Color(9, 134, 88),     // 亮色模式：森林绿
            new Color(107, 194, 152)   // 暗色模式：薄荷绿
    );

    // 字符串颜色 - 棕红色系
    private static final JBColor STRING_COLOR = new JBColor(
            new Color(163, 21, 21),    // 亮色模式：深红棕色
            new Color(206, 145, 120)   // 暗色模式：浅红棕色
    );

    // 类型颜色 - 青色系
    private static final JBColor TYPE_COLOR = new JBColor(
            new Color(0, 120, 120),    // 亮色模式：深青色
            new Color(78, 201, 176)    // 暗色模式：浅青色
    );

    // NULL值颜色 - 紫色系
    private static final JBColor NULL_COLOR = new JBColor(
            new Color(128, 0, 128),    // 亮色模式：紫色
            new Color(170, 128, 190)   // 暗色模式：浅紫色
    );

    // 标签颜色 - 深蓝色系
    private static final JBColor LABEL_COLOR = new JBColor(
            new Color(0, 64, 128),     // 亮色模式：深海蓝
            new Color(120, 156, 205)   // 暗色模式：钢蓝色
    );

    // 时间颜色 - 橙黄色系
    private static final JBColor TIME_COLOR = new JBColor(
            new Color(184, 134, 11),   // 亮色模式：黄色
            new Color(218, 165, 32)    // 暗色模式：金黄色
    );

    // 分隔符颜色 - 淡灰色系
    private static final JBColor SEPARATOR_COLOR = new JBColor(
            new Color(160, 160, 160),  // 亮色模式：中灰色
            new Color(100, 100, 100)   // 暗色模式：深灰色
    );

    // 字段名颜色 - 紫色系
    private static final JBColor FIELD_COLOR = new JBColor(
            new Color(120, 0, 160),    // 亮色模式：深紫色
            new Color(209, 129, 214)   // 暗色模式：淡紫色
    );

    // 补充颜色定义 - 客户特别要求的颜色
    private static final JBColor COMPLETE_SQL_COLOR = new JBColor(
            new Color(0, 128, 0),      // 亮色模式：绿色
            new Color(73, 156, 84)     // 暗色模式：深绿色
    );

    private static final JBColor PARAM_COLOR = new JBColor(
            new Color(128, 0, 128),    // 亮色模式：紫色
            new Color(175, 122, 197)   // 暗色模式：淡紫色
    );

    private static final JBColor TIME_LABEL_COLOR = new JBColor(
            new Color(184, 134, 11),   // 亮色模式：黄褐色
            new Color(218, 165, 32)    // 暗色模式：金黄色
    );

    // SQL 关键字正则表达式
    private static final Pattern SQL_KEYWORDS = Pattern.compile("\\b(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|AND|OR|INNER JOIN|LEFT JOIN|RIGHT JOIN|JOIN|GROUP BY|ORDER BY|HAVING|LIMIT|OFFSET|AS|ON|VALUES|SET|IN|BETWEEN|LIKE|IS NULL|IS NOT NULL|COUNT|SUM|AVG|MAX|MIN|DISTINCT|UNION|ALL)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_OPERATORS = Pattern.compile("(=|!=|<>|>|<|>=|<=|\\+|-|\\*|/|%|\\(|\\)|,|;)");
    private static final Pattern SQL_QUOTES = Pattern.compile("'[^']*'");
    private static final Pattern SQL_NUMBERS = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
    private static final Pattern SQL_TABLE_ALIAS = Pattern.compile("\\b[a-zA-Z0-9_]+\\.[a-zA-Z0-9_]+\\b");
    private static final Pattern SQL_FIELDS = Pattern.compile("\\b([a-zA-Z0-9_]+)\\b(?=\\s*[,=])");
    
    // 参数正则表达式
    private static final Pattern PARAM_INDEX = Pattern.compile("\\d+:");
    private static final Pattern PARAM_TYPE = Pattern.compile("\\(([A-Z]+)\\)");
    private static final Pattern PARAM_NULL = Pattern.compile("\\bnull\\b", Pattern.CASE_INSENSITIVE);
    
    // 表名后的字段列表正则
    private static final Pattern TABLE_FIELDS = Pattern.compile("(?<=FROM|INTO|UPDATE)\\s+\\w+\\s*\\(([^)]+)\\)");

    private final Project project;
    private JTextPane textPane;  // 使用标准的JTextPane
    private final ConcurrentLinkedQueue<LogEntry> logQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final List<LogEntry> allLogs = new ArrayList<>();
    private String currentFilter = "";

    // 日志条目类
    private static class LogEntry {
        final String type;
        final String content;
        
        LogEntry(String type, String content) {
            this.type = type;
            this.content = content;
        }
        
        @Override
        public String toString() {
            return content;
        }
    }

    public MyBatisLogManager(Project project) {
        this.project = project;
    }

    public static MyBatisLogManager getInstance(Project project) {
        return project.getService(MyBatisLogManager.class);
    }

    public void setTextPane(JTextPane textPane) {
        this.textPane = textPane;
        
        // 配置文本窗格
        StyledDocument doc = textPane.getStyledDocument();
        
        // 设置默认样式
        Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        StyleConstants.setFontFamily(defaultStyle, "JetBrains Mono".equals(UIManager.get("Editor.font.name")) 
                ? "JetBrains Mono" : "Monospaced");
        
        // 定义各种样式
        addStyle(doc, "default", defaultStyle);
        
        // 添加普通样式
        addColorStyle(doc, "keyword", KEYWORD_COLOR);
        addColorStyle(doc, "table", TABLE_COLOR);
        addColorStyle(doc, "operator", OPERATOR_COLOR);
        addColorStyle(doc, "function", FUNCTION_COLOR);
        addColorStyle(doc, "number", NUMBER_COLOR);
        addColorStyle(doc, "string", STRING_COLOR);
        addColorStyle(doc, "type", TYPE_COLOR);
        addColorStyle(doc, "null", NULL_COLOR);
        addColorStyle(doc, "label", LABEL_COLOR);
        addColorStyle(doc, "separator", SEPARATOR_COLOR);
        addColorStyle(doc, "field", FIELD_COLOR);
        addColorStyle(doc, "complete-sql", COMPLETE_SQL_COLOR);
        addColorStyle(doc, "param", PARAM_COLOR);
        addColorStyle(doc, "time", TIME_COLOR);
        
        // 添加粗体样式
        addBoldStyle(doc, "keyword-bold", KEYWORD_COLOR);
        addBoldStyle(doc, "table-bold", TABLE_COLOR);
        addBoldStyle(doc, "field-bold", FIELD_COLOR);
        addBoldStyle(doc, "label-bold", LABEL_COLOR);
        addBoldStyle(doc, "complete-sql-bold", COMPLETE_SQL_COLOR);
        addBoldStyle(doc, "param-bold", PARAM_COLOR);
        addBoldStyle(doc, "time-bold", TIME_COLOR);
    }
    
    private void addStyle(StyledDocument doc, String name, Style parent) {
        Style style = doc.addStyle(name, parent);
        StyleConstants.setFontFamily(style, "JetBrains Mono".equals(UIManager.get("Editor.font.name")) 
                ? "JetBrains Mono" : "Monospaced");
    }
    
    private void addColorStyle(StyledDocument doc, String name, Color color) {
        Style style = doc.addStyle(name, doc.getStyle("default"));
        StyleConstants.setForeground(style, color);
    }
    
    private void addBoldStyle(StyledDocument doc, String name, Color color) {
        Style style = doc.addStyle(name, doc.getStyle("default"));
        StyleConstants.setForeground(style, color);
        StyleConstants.setBold(style, true);
    }

    public void addLog(String log) {
        if (log == null || log.trim().isEmpty()) return;
        
        LogEntry entry;
        if (log.startsWith("SQL:")) {
            entry = new LogEntry("sql", log);
        } else if (log.startsWith("Parameters:")) {
            entry = new LogEntry("params", log);
        } else if (log.startsWith("Complete SQL:")) {
            entry = new LogEntry("complete", log);
        } else if (log.startsWith("Time:")) {
            entry = new LogEntry("time", log);
        } else if (log.startsWith("----")) {
            entry = new LogEntry("separator", log);
        } else {
            entry = new LogEntry("other", log);
        }
        
        logQueue.offer(entry);
        allLogs.add(entry);
        processLogs();
    }

    private void processLogs() {
        if (isProcessing.compareAndSet(false, true)) {
            try {
                if (textPane != null) {
                    LogEntry entry;
                    while ((entry = logQueue.poll()) != null) {
                        if (shouldShowLog(entry)) {
                            appendStyledLog(textPane, entry);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error processing logs", e);
            } finally {
                isProcessing.set(false);
            }
        }
    }

    private void appendStyledLog(JTextPane textPane, LogEntry entry) {
        try {
            StyledDocument doc = textPane.getStyledDocument();
            
            switch (entry.type) {
                case "sql":
                    appendSql(doc, entry.content);
                    break;
                case "params":
                    appendParameters(doc, entry.content);
                    break;
                case "complete":
                    appendCompleteSql(doc, entry.content);
                    break;
                case "time":
                    appendTime(doc, entry.content);
                    break;
                case "separator":
                    appendSeparator(doc, entry.content);
                    break;
                default:
                    appendDefault(doc, entry.content);
                    break;
            }
            
            // 添加换行
            doc.insertString(doc.getLength(), "\n", doc.getStyle("default"));
            
            // 自动滚动到底部
            textPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            LOG.error("Error appending styled log", e);
        }
    }
    
    private void appendSql(StyledDocument doc, String content) throws BadLocationException {
        // 提取前缀和实际SQL
        int colonIndex = content.indexOf(":");
        String prefix = content.substring(0, colonIndex + 1);
        String sql = content.substring(colonIndex + 1).trim();
        
        // 添加前缀（使用标准标签颜色）
        Style labelStyle = doc.getStyle("label-bold");
        doc.insertString(doc.getLength(), prefix + " ", labelStyle);
        
        // 使用默认颜色添加SQL
        doc.insertString(doc.getLength(), sql, doc.getStyle("default"));
    }
    
    private void appendParameters(StyledDocument doc, String content) throws BadLocationException {
        // 提取前缀和参数部分
        int colonIndex = content.indexOf(":");
        String prefix = content.substring(0, colonIndex + 1);
        String params = content.substring(colonIndex + 1).trim();
        
        // 添加前缀（使用紫色粗体）
        doc.insertString(doc.getLength(), prefix + " ", doc.getStyle("param-bold"));
        
        // 添加参数内容（也使用紫色）
        doc.insertString(doc.getLength(), params, doc.getStyle("param"));
    }
    
    private void appendCompleteSql(StyledDocument doc, String content) throws BadLocationException {
        // 提取前缀和完整SQL
        int colonIndex = content.indexOf(":");
        String prefix = content.substring(0, colonIndex + 1);
        String sql = content.substring(colonIndex + 1).trim();
        
        // 从SQL中移除参数类型标记
        sql = removeParamTypes(sql);
        
        // 添加前缀（使用粗体和背景色）
        Style labelStyle = doc.getStyle("label-bold");
        doc.insertString(doc.getLength(), prefix + " ", labelStyle);
        
        // 添加完整SQL(使用绿色)
        doc.insertString(doc.getLength(), sql, doc.getStyle("complete-sql-bold"));
    }
    
    private void appendTime(StyledDocument doc, String content) throws BadLocationException {
        // 添加前缀
        int colonIndex = content.indexOf(":");
        String prefix = content.substring(0, colonIndex + 1);
        String time = content.substring(colonIndex + 1).trim();
        
        // 使用黄色显示Time:标签
        doc.insertString(doc.getLength(), prefix + " ", doc.getStyle("time-bold"));
        
        // 使用黄色显示时间值
        doc.insertString(doc.getLength(), time, doc.getStyle("time"));
    }
    
    private void appendSeparator(StyledDocument doc, String content) throws BadLocationException {
        doc.insertString(doc.getLength(), content, doc.getStyle("separator"));
    }
    
    private void appendDefault(StyledDocument doc, String content) throws BadLocationException {
        doc.insertString(doc.getLength(), content, doc.getStyle("default"));
    }
    
    private void highlightSql(StyledDocument doc, String sql) throws BadLocationException {
        int startPos = doc.getLength();
        doc.insertString(startPos, sql, doc.getStyle("default"));
        
        // 高亮SQL关键字（使用粗体）
        highlightPattern(doc, sql, startPos, SQL_KEYWORDS, "keyword-bold");
        
        // 高亮表名（使用粗体）
        highlightPattern(doc, sql, startPos, SQL_TABLE_ALIAS, "table-bold");
        
        // 高亮字段名
        highlightFields(doc, sql, startPos);
        
        // 高亮操作符
        highlightPattern(doc, sql, startPos, SQL_OPERATORS, "operator");
        
        // 高亮字符串
        highlightPattern(doc, sql, startPos, SQL_QUOTES, "string");
        
        // 高亮数字
        highlightPattern(doc, sql, startPos, SQL_NUMBERS, "number");
    }

    /**
     * 高亮显示字段名
     */
    private void highlightFields(StyledDocument doc, String sql, int startPos) {
        // 高亮字段名
        try {
            // SELECT 子句中的字段名
            Pattern selectFields = Pattern.compile("(?<=SELECT)\\s+(.+?)(?=\\s+FROM)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher selectMatcher = selectFields.matcher(sql);
            if (selectMatcher.find()) {
                String fieldsStr = selectMatcher.group(1);
                // 分割字段（考虑函数和子查询）
                String[] fields = fieldsStr.split("\\s*,\\s*");
                for (String field : fields) {
                    // 提取别名和实际字段名
                    Pattern fieldPattern = Pattern.compile("\\b([a-zA-Z0-9_\\.]+)\\b(?!\\s*\\()", Pattern.CASE_INSENSITIVE);
                    Matcher fieldMatcher = fieldPattern.matcher(field);
                    while (fieldMatcher.find()) {
                        String fieldName = fieldMatcher.group(1);
                        if (!isKeyword(fieldName)) {  // 避免高亮SQL关键字
                            int fieldStart = startPos + selectMatcher.start(1) + field.indexOf(fieldName, 0);
                            if (fieldStart >= startPos && fieldStart < doc.getLength()) {
                                doc.setCharacterAttributes(fieldStart, fieldName.length(), 
                                        doc.getStyle("field-bold"), false);
                            }
                        }
                    }
                }
            }
            
            // WHERE, SET, VALUES 等子句中的字段名
            Pattern whereFields = Pattern.compile("\\b([a-zA-Z0-9_\\.]+)\\s*(=|<>|!=|>|<|>=|<=|LIKE|IN|IS)", Pattern.CASE_INSENSITIVE);
            Matcher whereMatcher = whereFields.matcher(sql);
            while (whereMatcher.find()) {
                String fieldName = whereMatcher.group(1);
                if (!isKeyword(fieldName) && !fieldName.matches(".*\\d+.*")) {
                    int fieldStart = startPos + whereMatcher.start(1);
                    if (fieldStart >= startPos && fieldStart < doc.getLength()) {
                        doc.setCharacterAttributes(fieldStart, fieldName.length(), 
                                doc.getStyle("field-bold"), false);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error highlighting fields", e);
        }
    }
    
    /**
     * 检查字段名是否为SQL关键字
     */
    private boolean isKeyword(String word) {
        String uppercaseWord = word.toUpperCase();
        return uppercaseWord.equals("SELECT") || uppercaseWord.equals("FROM") || 
               uppercaseWord.equals("WHERE") || uppercaseWord.equals("AND") || 
               uppercaseWord.equals("OR") || uppercaseWord.equals("JOIN") || 
               uppercaseWord.equals("GROUP") || uppercaseWord.equals("ORDER") || 
               uppercaseWord.equals("BY") || uppercaseWord.equals("HAVING") ||
               uppercaseWord.equals("LIMIT") || uppercaseWord.equals("OFFSET") ||
               uppercaseWord.equals("COUNT") || uppercaseWord.equals("SUM") ||
               uppercaseWord.equals("MIN") || uppercaseWord.equals("MAX") ||
               uppercaseWord.equals("AVG");
    }
    
    private void highlightParameters(StyledDocument doc, String params) throws BadLocationException {
        int startPos = doc.getLength();
        doc.insertString(startPos, params, doc.getStyle("default"));
        
        // 高亮参数索引 - 使用红褐色
        highlightPattern(doc, params, startPos, PARAM_INDEX, "param-name-bold");
        
        // 高亮参数类型
        highlightPattern(doc, params, startPos, PARAM_TYPE, "type");
        
        // 高亮字符串
        highlightPattern(doc, params, startPos, SQL_QUOTES, "string");
        
        // 高亮数字
        highlightPattern(doc, params, startPos, SQL_NUMBERS, "number");
        
        // 高亮NULL
        highlightPattern(doc, params, startPos, PARAM_NULL, "null");
    }
    
    private void highlightPattern(StyledDocument doc, String text, int startPos, Pattern pattern, String styleName) {
        Matcher matcher = pattern.matcher(text);
        Style style = doc.getStyle(styleName);
        
        while (matcher.find()) {
            int matchStart = startPos + matcher.start();
            int matchEnd = startPos + matcher.end();
            
            try {
                doc.setCharacterAttributes(matchStart, matchEnd - matchStart, style, false);
            } catch (Exception e) {
                LOG.error("Error highlighting pattern: " + pattern.pattern(), e);
            }
        }
    }

    public void filterLogs(String filter) {
        currentFilter = filter;
        refreshDisplay();
    }

    /**
     * 刷新所有日志显示
     * 在字体大小更改或过滤条件变化时调用
     */
    public void refreshDisplay() {
        if (textPane != null) {
            StyledDocument doc = textPane.getStyledDocument();
            try {
                doc.remove(0, doc.getLength());
                
                for (LogEntry entry : allLogs) {
                    if (shouldShowLog(entry)) {
                        appendStyledLog(textPane, entry);
                    }
                }
            } catch (BadLocationException e) {
                LOG.error("Error refreshing logs display", e);
            }
        }
    }

    private boolean shouldShowLog(LogEntry entry) {
        if (currentFilter.isEmpty()) return true;

        // 首先尝试查找表名
        if (entry.type.equals("sql") || entry.type.equals("complete")) {
            // 尝试从SQL提取表名
            String content = entry.content.toLowerCase();
            String searchText = currentFilter.toLowerCase();
            
            // 检查直接匹配
            if (content.contains(searchText)) {
                return true;
            }
            
            // 提取 FROM, JOIN, INTO, UPDATE 等子句后的表名
            Pattern tablePattern = Pattern.compile("\\b(from|join|update|into)\\s+([a-z0-9_\\.]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = tablePattern.matcher(content);
            
            while (matcher.find()) {
                String tableName = matcher.group(2).trim();
                if (tableName.contains(searchText)) {
                    return true;
                }
            }
            
            return false;
        }
        
        // 如果SQL语句匹配，则显示相关的参数和执行时间等
        int batchIndex = -1;
        if (entry.type.equals("params") || entry.type.equals("time") || entry.type.equals("separator")) {
            for (int i = allLogs.indexOf(entry) - 1; i >= 0; i--) {
                if (i < 0) break;
                
                LogEntry prevEntry = allLogs.get(i);
                if (prevEntry.type.equals("sql") || prevEntry.type.equals("complete")) {
                    if (shouldShowLog(prevEntry)) {
                        return true;
                    }
                    break;
                }
            }
        }
        
        return false;
    }

    public void clearLogs() {
        if (textPane != null) {
            StyledDocument doc = textPane.getStyledDocument();
            try {
                doc.remove(0, doc.getLength());
            } catch (BadLocationException e) {
                LOG.error("Error clearing logs", e);
            }
        }
        logQueue.clear();
        allLogs.clear();
    }
    
    /**
     * 移除SQL字符串中的参数类型标记
     * 例如: 'admin(String)' -> 'admin'
     */
    private String removeParamTypes(String sql) {
        // 处理字符串类型参数 - 匹配单引号中的内容
        Pattern stringPattern = Pattern.compile("'([^']*?)\\(([A-Za-z]+)\\)([^']*?)'");
        Matcher stringMatcher = stringPattern.matcher(sql);
        StringBuffer sb = new StringBuffer();
        
        while (stringMatcher.find()) {
            String replacement = "'" + stringMatcher.group(1) + stringMatcher.group(3) + "'";
            stringMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        stringMatcher.appendTail(sb);
        
        // 处理非字符串类型参数 - 数字、布尔值等不带引号的参数
        Pattern nonStringPattern = Pattern.compile("\\b([\\d\\.]+|true|false|null)\\(([A-Za-z]+)\\)\\b");
        Matcher nonStringMatcher = nonStringPattern.matcher(sb.toString());
        sb = new StringBuffer();
        
        while (nonStringMatcher.find()) {
            nonStringMatcher.appendReplacement(sb, nonStringMatcher.group(1));
        }
        nonStringMatcher.appendTail(sb);
        
        return sb.toString();
    }
} 