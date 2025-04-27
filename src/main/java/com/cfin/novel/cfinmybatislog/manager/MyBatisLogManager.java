package com.cfin.novel.cfinmybatislog.manager;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.components.Service;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.Disposable;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.UUID;

@Service(Service.Level.PROJECT)
public final class MyBatisLogManager implements Disposable {
    private static final Logger LOG = Logger.getInstance(MyBatisLogManager.class);

    // 性能优化配置
    private static final int MAX_LOG_ENTRIES = 5000; // 限制日志条目数量
    private static final int BATCH_PROCESS_SIZE = 20; // 每批处理的日志数量
    private static final long PROCESSING_DELAY_MS = 300; // 处理延迟（毫秒）
    private static final boolean LIMIT_OUTPUT = true; // 是否限制输出
    private static final int MAX_DOCUMENT_LENGTH = 500000; // 文档最大长度(字符)

    // 定义更丰富的颜色模式 - 使用现代UI设计风格的色彩
    // SQL关键字颜色 - 蓝色系 (更亮的蓝色以增强对比度)
    private static final JBColor KEYWORD_COLOR = new JBColor(
            new Color(0, 119, 255),    // 亮色模式：明亮蓝色
            new Color(86, 190, 255)    // 暗色模式：天蓝色
    );

    // 表名颜色 - 橙色系 (更饱和的橙色)
    private static final JBColor TABLE_COLOR = new JBColor(
            new Color(255, 120, 0),    // 亮色模式：亮橙色
            new Color(255, 165, 70)    // 暗色模式：金橙色
    );

    // 操作符颜色 - 灰色系 (增加对比度)
    private static final JBColor OPERATOR_COLOR = new JBColor(
            new Color(60, 60, 60),     // 亮色模式：深灰色
            new Color(200, 200, 200)   // 暗色模式：亮灰色
    );

    // 函数颜色 - 红色系 (更明亮的红色)
    private static final JBColor FUNCTION_COLOR = new JBColor(
            new Color(220, 30, 30),      // 亮色模式：鲜红色
            new Color(255, 100, 100)     // 暗色模式：亮红色
    );

    // 数值颜色 - 绿色系 (更明亮的绿色)
    private static final JBColor NUMBER_COLOR = new JBColor(
            new Color(0, 170, 0),      // 亮色模式：亮绿色
            new Color(107, 220, 104)   // 暗色模式：荧光绿
    );

    // 字符串颜色 - 褐色系 (更鲜明的颜色)
    private static final JBColor STRING_COLOR = new JBColor(
            new Color(205, 30, 30),    // 亮色模式：鲜红褐色
            new Color(255, 150, 150)   // 暗色模式：粉红色
    );

    // 类型颜色 - 紫色系 (更鲜艳的紫色)
    private static final JBColor TYPE_COLOR = new JBColor(
            new Color(160, 32, 240),    // 亮色模式：紫色
            new Color(210, 170, 255)    // 暗色模式：淡紫色
    );

    // NULL值颜色 - 灰色系 (更明亮的中性色)
    private static final JBColor NULL_COLOR = new JBColor(
            new Color(100, 100, 100),  // 亮色模式：中灰色
            new Color(180, 180, 180)   // 暗色模式：淡灰色
    );

    // 标签颜色 - 青色系 (更鲜明的青色)
    private static final JBColor LABEL_COLOR = new JBColor(
            new Color(0, 175, 175),    // 亮色模式：青绿色
            new Color(70, 230, 230)    // 暗色模式：亮青色
    );

    // 时间颜色 - 黄色系 (更鲜明的黄色)
    private static final JBColor TIME_COLOR = new JBColor(
            new Color(210, 160, 0),    // 亮色模式：金黄色
            new Color(255, 215, 90)    // 暗色模式：明黄色
    );

    // 分隔线颜色 - 浅灰色 (更明显的分割线)
    private static final JBColor SEPARATOR_COLOR = new JBColor(
            new Color(180, 180, 180),  // 亮色模式：浅灰色
            new Color(120, 120, 120)   // 暗色模式：深灰色
    );

    // 字段名颜色 - 绿蓝色系 (更鲜明的色彩)
    private static final JBColor FIELD_COLOR = new JBColor(
            new Color(0, 155, 155),    // 亮色模式：蓝绿色
            new Color(100, 225, 225)   // 暗色模式：亮蓝绿色
    );

    // 完整SQL颜色 - 青绿色系 (更加明显的完整SQL)
    private static final JBColor COMPLETE_SQL_COLOR = new JBColor(
            new Color(0, 135, 95),     // 亮色模式：深青绿色
            new Color(70, 195, 160)    // 暗色模式：明青绿色
    );

    // 参数颜色 - 紫色系 (更鲜明的参数颜色)
    private static final JBColor PARAM_COLOR = new JBColor(
            new Color(170, 40, 170),    // 亮色模式：亮紫色
            new Color(210, 150, 210)    // 暗色模式：淡紫色
    );

    // 时间标签颜色 - 黄绿色系 (更鲜明的时间标签)
    private static final JBColor TIME_LABEL_COLOR = new JBColor(
            new Color(160, 160, 0),    // 亮色模式：橄榄色
            new Color(200, 200, 60)    // 暗色模式：亮黄绿色
    );

    // SQL正则表达式
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
    private final List<LogEntry> allLogs = Collections.synchronizedList(new ArrayList<>());
    private String currentFilter = "";
    
    // 性能优化相关变量
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private ScheduledExecutorService logProcessor;
    private final ReentrantLock processingLock = new ReentrantLock();
    private boolean isInitialized = false;
    private long lastCleanupTime = System.currentTimeMillis();
    
    // 启用/禁用处理
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final ConcurrentLinkedQueue<LogEntry> pendingQueue = new ConcurrentLinkedQueue<>();

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
        // 默认启用日志管理器，避免错过日志
        this.enabled.set(true);
        LOG.info("MyBatisLogManager created and enabled for project: " + project.getName());
    }
    
    /**
     * 启用或禁用日志处理
     * @param enable true表示启用，false表示禁用
     */
    public void setEnabled(boolean enable) {
        boolean wasEnabled = enabled.getAndSet(enable);
        
        if (enable && !wasEnabled) {
            LOG.info("Enabling MyBatis Log Manager");
            // 如果之前禁用，现在启用，则处理所有挂起的日志
            processPendingLogs();
            
            // 确保处理器已初始化
            if (!isInitialized) {
                initializeProcessor();
            }
            
            // 清除初始提示消息
            if (textPane != null) {
                try {
                    StyledDocument doc = textPane.getStyledDocument();
                    doc.remove(0, doc.getLength());
                    doc.insertString(0, "MyBatis SQL Logger is active and waiting for logs...\n", doc.getStyle("label-bold"));
                } catch (BadLocationException e) {
                    LOG.error("Error updating text pane", e);
                }
            }
        } else if (!enable && wasEnabled) {
            LOG.info("Disabling MyBatis Log Manager");
        }
    }
    
    /**
     * 处理在禁用期间积累的日志
     */
    private void processPendingLogs() {
        LOG.info("Processing " + pendingQueue.size() + " pending logs");
        LogEntry entry;
        while ((entry = pendingQueue.poll()) != null) {
            logQueue.offer(entry);
            
            // 添加到总日志列表，并限制大小
            synchronized (allLogs) {
                allLogs.add(entry);
                // 限制最大日志数量
                while (allLogs.size() > MAX_LOG_ENTRIES) {
                    allLogs.remove(0);
                }
            }
        }
    }
    
    private void initializeProcessor() {
        if (isInitialized) return;
        
        LOG.info("Initializing MyBatis Log processor");
        // 创建后台处理线程
        logProcessor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "MyBatis-Log-Processor");
            thread.setPriority(Thread.MIN_PRIORITY); // 使用最低优先级
            thread.setDaemon(true); // 设置为守护线程，不阻止JVM退出
            return thread;
        });
        
        // 周期性处理日志队列
        logProcessor.scheduleWithFixedDelay(this::processQueuedLogs, 
            PROCESSING_DELAY_MS, PROCESSING_DELAY_MS, TimeUnit.MILLISECONDS);
        
        isInitialized = true;
    }

    public static MyBatisLogManager getInstance(Project project) {
        return project.getService(MyBatisLogManager.class);
    }

    public void setTextPane(JTextPane textPane) {
        if (!isInitialized) {
            initializeProcessor();
        }
        
        this.textPane = textPane;
        
        // 配置文本窗格
        StyledDocument doc = textPane.getStyledDocument();
        
        // 设置默认样式
        Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        StyleConstants.setFontFamily(defaultStyle, UIManager.getFont("Editor.font") != null ? 
                UIManager.getFont("Editor.font").getFamily() : 
                "JetBrains Mono".equals(UIManager.get("Editor.font.name")) ? 
                "JetBrains Mono" : "Monospaced");
        
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
        
        // 显示初始状态消息
        try {
            if (enabled.get()) {
                doc.insertString(0, "MyBatis SQL Logger is active and waiting for logs...\n", doc.getStyle("label-bold"));
            } else {
                doc.insertString(0, "MyBatis SQL Logger will start after application initialization completes...\n", doc.getStyle("label-bold"));
            }
            LOG.info("Added startup message to text pane");
        } catch (BadLocationException e) {
            LOG.error("Error adding startup message", e);
        }
    }
    
    private void addStyle(StyledDocument doc, String name, Style parent) {
        Style style = doc.addStyle(name, parent);
        StyleConstants.setFontFamily(style, UIManager.getFont("Editor.font") != null ? 
                UIManager.getFont("Editor.font").getFamily() : 
                "JetBrains Mono".equals(UIManager.get("Editor.font.name")) ? 
                "JetBrains Mono" : "Monospaced");
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
        
        if (!isInitialized) {
            initializeProcessor();
        }
        
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
        
        // 检查是否启用处理
        if (enabled.get()) {
            // 已启用，正常处理
            logQueue.offer(entry);
            
            // 添加到总日志列表，并限制大小
            synchronized (allLogs) {
                allLogs.add(entry);
                // 限制最大日志数量
                while (allLogs.size() > MAX_LOG_ENTRIES) {
                    allLogs.remove(0);
                }
            }
        } else {
            // 未启用，添加到挂起队列
            pendingQueue.offer(entry);
            
            // 限制挂起队列大小，避免内存问题
            while (pendingQueue.size() > MAX_LOG_ENTRIES) {
                pendingQueue.poll();
            }
        }
    }
    
    private void processQueuedLogs() {
        // 如果未启用，则不处理任何内容
        if (!enabled.get()) {
            return;
        }
        
        if (!processingLock.tryLock()) {
            return; // 如果已有线程在处理，直接返回
        }
        
        try {
            if (textPane == null || textPane.getDocument() == null) return;
            
            int processedCount = 0;
            LogEntry entry;
            final List<LogEntry> batch = new ArrayList<>(BATCH_PROCESS_SIZE);
            
            // 收集一批日志
            while ((entry = logQueue.poll()) != null && processedCount < BATCH_PROCESS_SIZE) {
                if (shouldShowLog(entry)) {
                    batch.add(entry);
                }
                processedCount++;
            }
            
            // 如果有日志需要显示，在EDT线程中批量处理
            if (!batch.isEmpty()) {
                final StyledDocument doc = textPane.getStyledDocument();
                
                // 检查是否需要清理过长的文档(每10秒检查一次)
                long currentTime = System.currentTimeMillis();
                if (LIMIT_OUTPUT && currentTime - lastCleanupTime > 10000) {
                    cleanupTextPane(doc);
                    lastCleanupTime = currentTime;
                }
                
                SwingUtilities.invokeLater(() -> {
                    try {
                        // 分组处理相关的SQL日志条目
                        List<LogEntry> sortedBatch = groupAndSortLogEntries(batch);
                        
                        for (LogEntry logEntry : sortedBatch) {
                            appendStyledLog(textPane, logEntry);
                        }
                        
                        // 自动滚动到底部
                        if (!sortedBatch.isEmpty()) {
                            textPane.setCaretPosition(doc.getLength());
                        }
                    } catch (Exception e) {
                        LOG.error("Error batch processing logs", e);
                    }
                });
            }
        } catch (Exception e) {
            LOG.error("Error during log processing", e);
        } finally {
            processingLock.unlock();
        }
    }
    
    /**
     * 分组并排序日志条目，确保相关SQL日志条目一起显示
     */
    private List<LogEntry> groupAndSortLogEntries(List<LogEntry> batch) {
        Map<String, List<LogEntry>> groups = new HashMap<>();
        List<LogEntry> result = new ArrayList<>(batch.size());
        List<LogEntry> separators = new ArrayList<>();
        String currentGroup = null;
        int groupCounter = 0;
        
        // 预处理步骤：按添加顺序分配组ID，确保不会打乱原始顺序
        Map<String, Integer> typeGroups = new HashMap<>();
        for (LogEntry entry : batch) {
            if (entry.type.equals("separator")) {
                separators.add(entry);
                continue;
            }
            
            if (entry.type.equals("sql")) {
                // 新SQL开始了一个新组
                currentGroup = "group_" + (++groupCounter);
                typeGroups.put(currentGroup, 1);
            } else if (currentGroup != null) {
                // 增加当前组中的类型计数
                typeGroups.put(currentGroup, typeGroups.getOrDefault(currentGroup, 0) + 1);
            }
            
            if (currentGroup != null) {
                if (!groups.containsKey(currentGroup)) {
                    groups.put(currentGroup, new ArrayList<>());
                }
                groups.get(currentGroup).add(entry);
            } else {
                // 没有组的条目直接添加到结果
                result.add(entry);
            }
        }
        
        // 按照SQL, Parameters, Complete SQL, Time的顺序排序每个组
        for (Map.Entry<String, List<LogEntry>> groupEntry : groups.entrySet()) {
            List<LogEntry> group = groupEntry.getValue();
            
            // 只有当组中有多个元素且包含多种类型时才需要排序
            if (group.size() > 1 && typeGroups.getOrDefault(groupEntry.getKey(), 0) > 1) {
                Collections.sort(group, (a, b) -> {
                    int aOrder = getTypeOrder(a.type);
                    int bOrder = getTypeOrder(b.type);
                    return Integer.compare(aOrder, bOrder);
                });
            }
            
            result.addAll(group);
            
            // 在每个完整的组后添加一个分隔符，除非这是最后一个组
            if (isCompleteGroup(group) && !groupEntry.getKey().equals("group_" + groupCounter)) {
                LogEntry separator = new LogEntry("separator", "----------------------------------------");
                result.add(separator);
            }
        }
        
        // 如果还有剩余的分隔符并且不是在每个组后已添加，则添加它们
        if (!separators.isEmpty() && !result.isEmpty() && 
            !result.get(result.size() - 1).type.equals("separator")) {
            result.addAll(separators);
        }
        
        return result;
    }
    
    /**
     * 检查一个组是否包含完整的SQL执行信息
     */
    private boolean isCompleteGroup(List<LogEntry> group) {
        boolean hasSql = false;
        boolean hasParams = false;
        boolean hasComplete = false;
        
        for (LogEntry entry : group) {
            if (entry.type.equals("sql")) hasSql = true;
            else if (entry.type.equals("params")) hasParams = true;
            else if (entry.type.equals("complete")) hasComplete = true;
        }
        
        return hasSql && hasParams && hasComplete;
    }
    
    /**
     * 获取日志类型的排序优先级
     */
    private int getTypeOrder(String type) {
        switch (type) {
            case "sql": return 1;
            case "params": return 2;
            case "complete": return 3;
            case "time": return 4;
            case "separator": return 5;
            default: return 6;
        }
    }
    
    private void cleanupTextPane(StyledDocument doc) {
        if (doc.getLength() > MAX_DOCUMENT_LENGTH) {
            try {
                // 删除文档开头部分，保留后面的内容
                int charsToRemove = doc.getLength() - (MAX_DOCUMENT_LENGTH / 2);
                if (charsToRemove > 0) {
                    SwingUtilities.invokeLater(() -> {
                        try {
                            doc.remove(0, charsToRemove);
                            LOG.info("Cleaned up text pane, removed " + charsToRemove + " characters");
                        } catch (BadLocationException e) {
                            LOG.error("Error cleaning up text pane", e);
                        }
                    });
                }
            } catch (Exception e) {
                LOG.error("Error evaluating document length", e);
            }
        }
    }

    private void processLogs() {
        // 该方法保留用于兼容性，实际处理由调度任务完成
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
        synchronized (allLogs) {
            allLogs.clear();
        }
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

    @Override
    public void dispose() {
        if (logProcessor != null) {
            logProcessor.shutdown();
            try {
                if (!logProcessor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    logProcessor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logProcessor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        clearLogs();
        LOG.info("MyBatisLogManager disposed for project: " + project.getName());
    }

    // 添加一个公开的isEnabled方法，让过滤器可以快速检查状态
    public boolean isEnabled() {
        return enabled.get();
    }
} 