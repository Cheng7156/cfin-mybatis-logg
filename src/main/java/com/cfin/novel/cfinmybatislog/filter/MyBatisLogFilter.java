package com.cfin.novel.cfinmybatislog.filter;

import com.cfin.novel.cfinmybatislog.manager.MyBatisLogManager;
import com.intellij.execution.filters.Filter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyBatisLogFilter implements Filter {
    private static final Logger LOG = Logger.getInstance(MyBatisLogFilter.class);
    
    // 扩展匹配模式以支持更多 MyBatis 日志格式 - 使用预编译以提高性能
    private static final Pattern SQL_PATTERN = Pattern.compile("(?i)(Preparing:|Parameters:|==>\\s*Preparing:|==>\\s*Parameters:|\\[\\s*mybatis\\s*\\].*?Preparing:|\\[\\s*mybatis\\s*\\].*?Parameters:|Executing query|Execute SQL)");
    private static final Pattern CLEAR_SQL_PATTERN = Pattern.compile("(?i)(Preparing:|==>\\s*Preparing:|\\[\\s*mybatis\\s*\\].*?Preparing:|Executing query|Execute SQL)");
    private static final Pattern PARAMETERS_PATTERN = Pattern.compile("(?i)(Parameters:|==>\\s*Parameters:|\\[\\s*mybatis\\s*\\].*?Parameters:)");
    
    // 优化参数值提取的正则表达式
    private static final Pattern PARAM_VALUE_PATTERN = Pattern.compile("(?i)\\((\\w+)\\) (.*?)(?=, \\(|$)");
    private static final Pattern SIMPLE_PARAM_PATTERN = Pattern.compile("(?i)null|\\d+|(\\d+\\.\\d+)|'.*?'|true|false");
    
    private static final Pattern TIME_PATTERN = Pattern.compile("(?i)Time: (\\d+)ms|Executed in (\\d+)ms|\\[\\s*mybatis\\s*\\].*?(\\d+)ms");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // 扩展匹配 Spring Boot MyBatis 日志格式
    private static final Pattern SPRING_BOOT_SQL_PATTERN = Pattern.compile("(?i)(\\[\\s*\\w+\\s*\\]\\s*DEBUG\\s*.*?Preparing:|\\[\\s*\\w+\\s*\\]\\s*DEBUG\\s*.*?Parameters:|.*?DEBUG.*?Preparing:|.*?DEBUG.*?Parameters:|.*?com\\.\\w+\\.\\w+\\.mapper.*?Preparing:|.*?org\\.apache\\.ibatis\\..*?Preparing:|.*?mybatis\\..*?Preparing:)");
    private static final Pattern SPRING_BOOT_SQL = Pattern.compile("(?i)(\\[\\s*\\w+\\s*\\]\\s*DEBUG\\s*.*?Preparing:|.*?DEBUG.*?Preparing:|.*?com\\.\\w+\\.\\w+\\.mapper.*?Preparing:|.*?org\\.apache\\.ibatis\\..*?Preparing:|.*?mybatis\\..*?Preparing:)");
    private static final Pattern SPRING_BOOT_PARAMS = Pattern.compile("(?i)(\\[\\s*\\w+\\s*\\]\\s*DEBUG\\s*.*?Parameters:|.*?DEBUG.*?Parameters:|.*?com\\.\\w+\\.\\w+\\.mapper.*?Parameters:|.*?org\\.apache\\.ibatis\\..*?Parameters:|.*?mybatis\\..*?Parameters:)");

    // 专门匹配Spring Boot中mapper包的日志
    private static final Pattern MAPPER_LOG_PATTERN = Pattern.compile("(?i).*\\b(mapper|dao|repository)\\b.*");

    // 使用实例变量存储状态，每个SQL执行使用一个唯一的标识跟踪
    private static class SqlExecution {
        String id;
        String sql;
        String params;
        String time;
        LocalDateTime timestamp;
        
        SqlExecution(String id) {
            this.id = id;
            this.timestamp = LocalDateTime.now();
        }
        
        boolean isComplete() {
            return sql != null && params != null;
        }
        
        @Override
        public String toString() {
            return "SqlExecution{" +
                   "id='" + id + '\'' +
                   ", sql='" + (sql != null ? sql.substring(0, Math.min(30, sql.length())) + "..." : "null") + '\'' +
                   ", params='" + params + '\'' +
                   ", time='" + time + '\'' +
                   '}';
        }
    }
    
    // 使用线程安全队列来存储和处理SQL执行信息
    private final ConcurrentLinkedQueue<SqlExecution> pendingSqlExecutions = new ConcurrentLinkedQueue<>();
    private final ReentrantLock processingLock = new ReentrantLock();
    private final Project project;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // 用于连续SQL处理的时间阈值 - 1秒内的日志被视为同一SQL组
    private static final long SQL_GROUP_TIME_THRESHOLD_MS = 1000;

    public MyBatisLogFilter(Project project) {
        this.project = project;
        LOG.info("MyBatisLogFilter initialized for project: " + project.getName());
    }

    public void dispose() {
        LOG.info("Disposing MyBatisLogFilter for project: " + project.getName());
        pendingSqlExecutions.clear();
    }

    @Nullable
    @Override
    public Result applyFilter(@NotNull String line, int entireLength) {
        // 快速检查：如果不是SQL日志，立即返回
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        // 快速检查：如果是INSERT语句，立即跳过
        if (line.toUpperCase().contains("INSERT INTO") || 
            line.trim().toUpperCase().startsWith("INSERT") ||
            (line.contains("Preparing:") && line.toUpperCase().contains("INSERT"))) {
            return null;  // 跳过所有INSERT语句
        }

        // 获取日志管理器实例
        MyBatisLogManager manager = MyBatisLogManager.getInstance(project);
        
        // 先尝试检查这是否是任何可能的SQL日志行
        boolean mightBeSqlLog = line.contains("SQL") ||
                               line.contains("Preparing") ||
                               line.contains("Parameters") ||
                               line.contains("mybatis") ||
                               line.contains("Executed") ||
                               line.contains("Total:") ||
                               line.contains("==>") ||
                               line.contains("Mapper") ||
                               MAPPER_LOG_PATTERN.matcher(line).matches() ||
                               line.contains("org.apache.ibatis") ||
                               line.matches("(?i).*select.*from.*") ||
                               line.matches("(?i).*update.*set.*") ||
                               line.matches("(?i).*delete.*from.*");

        if (!mightBeSqlLog) {
            return null;  // 快速丢弃明显不是SQL日志的行
        }
        
        // 输出日志行到IDE的调试日志中，帮助开发人员调试
        LOG.info("Potential SQL log: " + line.substring(0, Math.min(100, line.length())));

        // 更全面地检查是否为SQL日志
        boolean isSqlLog = SQL_PATTERN.matcher(line).find() ||
                         SPRING_BOOT_SQL_PATTERN.matcher(line).find() ||
                         TIME_PATTERN.matcher(line).find() ||
                         // 增加更多模式匹配
                         line.contains("Preparing: ") ||
                         line.contains("Parameters: ") ||
                         line.contains("==>  Preparing: ") ||
                         line.contains("==> Parameters: ") ||
                         // 更多Spring Boot特定匹配
                         line.contains("DEBUG") && (line.contains("Preparing") || line.contains("Parameters"));

        if (!isSqlLog) {
            // 尝试匹配直接SQL语句
            if (line.trim().toUpperCase().startsWith("SELECT") ||
                line.trim().toUpperCase().startsWith("UPDATE") ||
                line.trim().toUpperCase().startsWith("DELETE")) {
                isSqlLog = true;
            } else {
                return null;  // 不是SQL日志，丢弃
            }
        }
        
        // 日志管理器默认启用，确保能处理日志
        if (!manager.isEnabled()) {
            LOG.info("Enabling MyBatis Log Manager");
            manager.setEnabled(true);
        }
        
        // 在后台线程中处理SQL日志，而不是阻塞过滤器处理
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            processLine(line);
        });
        
        // 返回 null 表示不进行高亮或其他处理
        return null;
    }
    
    private void processLine(String line) {
        try {
            MyBatisLogManager manager = MyBatisLogManager.getInstance(project);

            // 如果日志管理器未启用，快速返回
            if (!manager.isEnabled()) {
                LOG.info("MyBatis Log Manager is not enabled, skipping log: " + line);
                return;
            }
            
            // 检查是否为INSERT语句，如果是则跳过
            if (line.toUpperCase().contains("INSERT INTO") || 
                line.trim().toUpperCase().startsWith("INSERT") ||
                (line.contains("Preparing:") && line.toUpperCase().contains("INSERT"))) {
                LOG.info("Skipping INSERT statement: " + line.substring(0, Math.min(50, line.length())));
                return;  // 跳过所有INSERT语句
            }
            
            LOG.info("Processing SQL log line: " + line.substring(0, Math.min(50, line.length())) + "...");
            
            // 处理SQL语句
            if (CLEAR_SQL_PATTERN.matcher(line).find() || SPRING_BOOT_SQL.matcher(line).find() ||
                line.contains("Preparing: ") || line.contains("==>  Preparing: ")) {
                String sql = extractSql(line);
                if (sql != null && !sql.isEmpty()) {
                    // 再次检查提取的SQL是否为INSERT语句
                    if (sql.toUpperCase().trim().startsWith("INSERT")) {
                        LOG.info("Skipping extracted INSERT SQL: " + sql.substring(0, Math.min(50, sql.length())));
                        return;
                    }
                    
                    // 创建新的SQL执行记录
                    SqlExecution execution = new SqlExecution(UUID.randomUUID().toString());
                    execution.sql = sql;
                    pendingSqlExecutions.offer(execution);
                    manager.addLog("SQL: " + sql);
                    LOG.info("Added SQL: " + sql.substring(0, Math.min(50, sql.length())) + "...");
                    LOG.info("Current pending SQL executions: " + pendingSqlExecutions.size());
                }
            } 
            // 处理参数
            else if (PARAMETERS_PATTERN.matcher(line).find() || SPRING_BOOT_PARAMS.matcher(line).find() ||
                     line.contains("Parameters: ") || line.contains("==> Parameters: ")) {
                String params = extractParams(line);
                if (params != null && !params.isEmpty()) {
                    // 查找最近的SQL执行记录
                    SqlExecution latestExecution = findMatchingExecutionForParams();
                    
                    // 如果没有找到对应的SQL执行记录，则跳过这个参数
                    if (latestExecution == null || latestExecution.sql == null) {
                        LOG.info("No matching SQL found for parameters, skipping: " + params);
                        return;
                    }
                    
                    // 检查对应的SQL是否是INSERT语句
                    if (latestExecution.sql != null && latestExecution.sql.toUpperCase().trim().startsWith("INSERT")) {
                        LOG.info("Parameters belong to an INSERT statement, skipping");
                        pendingSqlExecutions.remove(latestExecution);
                        return;
                    }
                    
                    // 记录匹配到参数的SQL语句，便于调试
                    LOG.info("Matched parameters to SQL: " + latestExecution.toString());
                    
                    latestExecution.params = params;
                    String formattedParams = formatParameters(params);
                    manager.addLog("Parameters: " + formattedParams);
                    LOG.info("Added Parameters: " + formattedParams);
                    
                    // 生成并显示完整SQL
                    String completeSql = generateCompleteSql(latestExecution.sql, params);
                    manager.addLog("Complete SQL: " + completeSql);
                    LOG.info("Added Complete SQL");
                    
                    // 显示执行时间
                    String time = latestExecution.time != null ? latestExecution.time : "0";
                    String timestamp = latestExecution.timestamp.format(DATE_FORMATTER);
                    manager.addLog("Time: " + time + "ms (" + timestamp + ")");
                    manager.addLog("----------------------------------------");
                    
                    // 处理完成后立即移除，防止错误匹配
                    pendingSqlExecutions.remove(latestExecution);
                    LOG.info("Removed executed SQL from queue. Remaining: " + pendingSqlExecutions.size());
                }
            } 
            // 捕获执行时间
            else if (TIME_PATTERN.matcher(line).find() || line.contains("Time:") || line.contains("Executed in")) {
                Matcher matcher = TIME_PATTERN.matcher(line);
                if (matcher.find()) {
                    String time = null;
                    for (int i = 1; i <= matcher.groupCount(); i++) {
                        if (matcher.group(i) != null) {
                            time = matcher.group(i);
                            break;
                        }
                    }
                    
                    if (time != null) {
                        // 更新最近的执行记录，使用专门为时间查找的方法
                        SqlExecution latestExecution = findMatchingExecutionForTime();
                        if (latestExecution != null) {
                            latestExecution.time = time;
                            LOG.info("Updated execution time: " + time + "ms for SQL: " + latestExecution.toString());
                        } else {
                            // 如果没有找到对应的SQL执行记录，则忽略这个时间
                            LOG.info("No matching SQL found for execution time: " + time + "ms, ignoring");
                        }
                    }
                }
            } 
            // 尝试捕获直接SQL语句
            else if (line.trim().toUpperCase().startsWith("SELECT") || 
                     line.trim().toUpperCase().startsWith("UPDATE") || 
                     line.trim().toUpperCase().startsWith("DELETE")) {
                // 创建新的SQL执行记录
                SqlExecution execution = new SqlExecution(UUID.randomUUID().toString());
                execution.sql = line.trim();
                pendingSqlExecutions.offer(execution);
                manager.addLog("SQL: " + line.trim());
                LOG.info("Added direct SQL statement");
            }
            
            // 清理过期的SQL执行记录
            cleanupOldExecutions();
            
        } catch (Exception e) {
            LOG.error("Error processing line: " + line, e);
        }
    }
    
    /**
     * 查找匹配参数的SQL执行记录
     * 此方法优先查找尚未有参数的SQL记录
     */
    private SqlExecution findMatchingExecutionForParams() {
        // 1. 优先找尚未有参数的SQL记录
        for (SqlExecution execution : pendingSqlExecutions) {
            if (execution.sql != null && execution.params == null) {
                return execution;
            }
        }
        
        // 2. 如果没有找到，返回null
        return null;
    }
    
    /**
     * 查找匹配时间的SQL执行记录
     * 此方法会查找最新添加的且还未被移除的SQL记录，无论它是否已有参数
     */
    private SqlExecution findMatchingExecutionForTime() {
        if (pendingSqlExecutions.isEmpty()) {
            return null;
        }
        
        // 优先查找已有SQL和参数的记录
        for (SqlExecution execution : pendingSqlExecutions) {
            if (execution.sql != null && execution.params != null && execution.time == null) {
                return execution;
            }
        }
        
        // 其次查找只有SQL的记录
        for (SqlExecution execution : pendingSqlExecutions) {
            if (execution.sql != null && execution.time == null) {
                return execution;
            }
        }
        
        // 最后返回队列中最后一个记录
        return pendingSqlExecutions.peek();
    }
    
    /**
     * 清理过期的SQL执行记录
     */
    private void cleanupOldExecutions() {
        LocalDateTime now = LocalDateTime.now();
        int beforeSize = pendingSqlExecutions.size();
        pendingSqlExecutions.removeIf(execution -> 
            java.time.Duration.between(execution.timestamp, now).toMillis() > SQL_GROUP_TIME_THRESHOLD_MS * 10);
        int afterSize = pendingSqlExecutions.size();
        
        if (beforeSize != afterSize) {
            LOG.info("Cleaned up " + (beforeSize - afterSize) + " old SQL executions");
        }
        
        // 如果队列不为空，打印当前状态
        if (!pendingSqlExecutions.isEmpty()) {
            debugPrintSqlQueue();
        }
    }
    
    /**
     * 打印当前SQL队列的状态，便于调试
     */
    private void debugPrintSqlQueue() {
        if (pendingSqlExecutions.isEmpty()) {
            LOG.info("SQL execution queue is empty");
            return;
        }
        
        StringBuilder sb = new StringBuilder("Current SQL queue state (" + pendingSqlExecutions.size() + " items):\n");
        int index = 0;
        for (SqlExecution execution : pendingSqlExecutions) {
            sb.append(index++).append(": ").append(execution.toString()).append("\n");
        }
        LOG.info(sb.toString());
    }

    private String extractSql(String text) {
        try {
            // 先记录日志，方便调试
            LOG.info("Extracting SQL from: " + text.substring(0, Math.min(80, text.length())));

            // 尝试从不同格式的日志中提取 SQL
            int index = -1;
            if (text.contains("Preparing:")) {
                index = text.indexOf("Preparing:") + "Preparing:".length();
            } else if (text.contains("==> Preparing:")) {
                index = text.indexOf("==> Preparing:") + "==> Preparing:".length();
            } else if (text.contains("==>  Preparing:")) {
                index = text.indexOf("==>  Preparing:") + "==>  Preparing:".length();
            } else if (text.contains("Execute SQL")) {
                index = text.indexOf("Execute SQL") + "Execute SQL".length();
            } else if (text.contains("Executing query")) {
                index = text.indexOf("Executing query") + "Executing query".length();
            } else if (text.matches("(?i).*DEBUG.*Preparing:.*")) {
                // 处理 DEBUG 日志格式
                index = text.toLowerCase().lastIndexOf("preparing:") + "preparing:".length();
            }

            if (index > 0 && index < text.length()) {
                String extractedSql = text.substring(index).trim();
                LOG.info("Extracted SQL: " + extractedSql.substring(0, Math.min(50, extractedSql.length())));
                return extractedSql;
            }
            
            // 尝试查找行中的第一个SQL关键字
            String[] sqlKeywords = {"SELECT", "UPDATE", "DELETE"};
            for (String keyword : sqlKeywords) {
                int keywordIndex = text.toUpperCase().indexOf(keyword);
                if (keywordIndex >= 0) {
                    String extractedSql = text.substring(keywordIndex).trim();
                    LOG.info("Extracted SQL using keyword: " + extractedSql.substring(0, Math.min(50, extractedSql.length())));
                    return extractedSql;
                }
            }
            
            LOG.info("Unable to extract SQL, using whole line: " + text.substring(0, Math.min(50, text.length())));
            return text.trim();
        } catch (Exception e) {
            LOG.error("Error extracting SQL: " + text, e);
            return text.trim();
        }
    }

    private String extractParams(String text) {
        try {
            // 先记录日志，方便调试
            LOG.info("Extracting parameters from: " + text.substring(0, Math.min(80, text.length())));

            // 尝试从不同格式的日志中提取参数
            int index = -1;
            if (text.contains("Parameters:")) {
                index = text.indexOf("Parameters:") + "Parameters:".length();
            } else if (text.contains("==> Parameters:")) {
                index = text.indexOf("==> Parameters:") + "==> Parameters:".length();
            } else if (text.contains("==>  Parameters:")) {
                index = text.indexOf("==>  Parameters:") + "==>  Parameters:".length();
            } else if (text.matches("(?i).*DEBUG.*Parameters:.*")) {
                index = text.toLowerCase().lastIndexOf("parameters:") + "parameters:".length();
            }

            if (index > 0 && index < text.length()) {
                String extractedParams = text.substring(index).trim();
                LOG.info("Extracted parameters: " + extractedParams);
                return extractedParams;
            }
            
            // 如果找不到正式的参数标记，尝试直接查找参数
            Matcher matcher = SIMPLE_PARAM_PATTERN.matcher(text);
            if (matcher.find()) {
                LOG.info("Extracted parameters using simple pattern: " + text.trim());
                return text.trim();
            }
            
            LOG.info("Unable to extract parameters, using whole line: " + text.substring(0, Math.min(50, text.length())));
            return text.trim();
        } catch (Exception e) {
            LOG.error("Error extracting parameters: " + text, e);
            return text.trim();
        }
    }

    private String formatParameters(String params) {
        if (params == null || params.isEmpty()) return "[]";
        
        try {
            LOG.info("Formatting parameters: " + params);
            
            // 检查是否是已经格式化的批量参数，如 [1: xxx(Type), 2: yyy(Type)]
            if (params.trim().startsWith("[") && params.trim().endsWith("]")) {
                LOG.info("Parameters already in formatted batch format, returning as is");
                return params;
            }
            
            Matcher matcher = PARAM_VALUE_PATTERN.matcher(params);
            StringBuilder formattedParams = new StringBuilder();
            formattedParams.append("[");
            
            int paramIndex = 1;
            boolean foundParams = false;
            
            while (matcher.find()) {
                foundParams = true;
                String paramType = matcher.group(1);
                String paramValue = matcher.group(2).trim();
                
                if (formattedParams.length() > 1) {
                    formattedParams.append(", ");
                }
                
                formattedParams.append(paramIndex).append(": ").append(paramValue)
                               .append(" (").append(paramType).append(")");
                paramIndex++;
            }
            
            // 如果没找到带类型的参数，尝试简单参数格式
            if (!foundParams) {
                LOG.info("No typed parameters found, trying simple format parsing");
                
                // 检查是否只有一个参数值，无需分割
                if (!params.contains(",")) {
                    String value = params.trim();
                    String paramType = getParamType(value);
                    formattedParams.append("1: ").append(value)
                                   .append(" (").append(paramType).append(")");
                    LOG.info("Single parameter detected: " + value + " of type " + paramType);
                } else {
                    // 处理简单参数列表，如 1, 'string', null
                    String[] simpleParams = params.split(",");
                    for (int i = 0; i < simpleParams.length; i++) {
                        String value = simpleParams[i].trim();
                        if (value.isEmpty()) continue;
                        
                        if (i > 0) {
                            formattedParams.append(", ");
                        }
                        
                        String paramType = getParamType(value);
                        formattedParams.append(i + 1).append(": ").append(value)
                                       .append(" (").append(paramType).append(")");
                        LOG.info("Parameter #" + (i + 1) + ": " + value + " of type " + paramType);
                    }
                }
            }
            
            formattedParams.append("]");
            LOG.info("Formatted parameters result: " + formattedParams.toString());
            return formattedParams.toString();
        } catch (Exception e) {
            LOG.error("Error formatting parameters: " + params, e);
            return "[" + params + "]";
        }
    }

    private static String getParamType(String paramValue) {
        if (paramValue == null) return "NULL";
        if (paramValue.equalsIgnoreCase("null")) return "NULL";
        if (paramValue.matches("^-?\\d+$")) return "INTEGER";
        if (paramValue.matches("^-?\\d+\\.\\d+$")) return "DECIMAL";
        if (paramValue.matches("^'.*'$")) return "STRING";
        if (paramValue.equalsIgnoreCase("true") || paramValue.equalsIgnoreCase("false")) return "BOOLEAN";
        if (paramValue.matches("^\\d{4}-\\d{2}-\\d{2}.*$")) return "DATE";
        return "STRING";
    }

    private static String generateCompleteSql(String sql, String params) {
        if (sql == null || params == null || params.isEmpty()) return sql;
        
        try {
            LOG.info("Generating complete SQL with params: " + params);
            
            // 判断参数是否是以中括号开头的格式，如 [1: value1(Type), 2: value2(Type)]
            // 这种情况下，我们需要解析出每个单独的参数
            boolean isBatchParameters = params.trim().startsWith("[") && params.trim().endsWith("]");
            
            // 保存所有参数值到列表中，便于后续处理
            List<String> paramValues = new ArrayList<>();
            List<String> paramTypes = new ArrayList<>();
            
            if (isBatchParameters) {
                // 提取中括号内的内容
                String paramContent = params.substring(params.indexOf("[") + 1, params.lastIndexOf("]")).trim();
                LOG.info("Batch parameters content: " + paramContent);
                
                // 尝试使用PARAM_VALUE_PATTERN匹配参数
                Matcher matcher = Pattern.compile("(\\d+):\\s*([^(]+)\\(([^)]+)\\)").matcher(paramContent);
                while (matcher.find()) {
                    String paramIndex = matcher.group(1);
                    String paramValue = matcher.group(2).trim();
                    String paramType = matcher.group(3).trim();
                    LOG.info("Found parameter #" + paramIndex + ": " + paramValue + " (" + paramType + ")");
                    paramValues.add(paramValue);
                    paramTypes.add(paramType);
                }
                
                // 如果没有找到格式化的参数，尝试按逗号分隔
                if (paramValues.isEmpty()) {
                    LOG.info("No formatted parameters found, trying comma separation");
                    String[] parts = paramContent.split(",");
                    for (String part : parts) {
                        part = part.trim();
                        if (!part.isEmpty()) {
                            paramValues.add(part);
                            paramTypes.add(getParamType(part));
                            LOG.info("Added parameter: " + part);
                        }
                    }
                }
            } else {
                // 简单参数，不是批量格式
                // 尝试使用PARAM_VALUE_PATTERN匹配参数
                Matcher matcher = PARAM_VALUE_PATTERN.matcher(params);
                boolean paramFound = false;
                
                while (matcher.find()) {
                    paramFound = true;
                    String paramType = matcher.group(1);
                    String paramValue = matcher.group(2).trim();
                    paramValues.add(paramValue);
                    paramTypes.add(paramType);
                }
                
                // 如果没找到带类型的参数，尝试简单参数格式
                if (!paramFound) {
                    // 处理简单参数列表，如 1, 'string', null
                    String[] simpleParams = params.split(",");
                    for (String value : simpleParams) {
                        value = value.trim();
                        if (!value.isEmpty()) {
                            paramValues.add(value);
                            paramTypes.add(getParamType(value));
                        }
                    }
                }
            }
            
            // 如果没有参数，直接返回原始SQL
            if (paramValues.isEmpty()) {
                LOG.warn("No parameters extracted, returning original SQL");
                return sql;
            }
            
            // 记录参数信息，便于诊断
            LOG.info("Parsed " + paramValues.size() + " parameters for SQL: " + 
                     sql.substring(0, Math.min(50, sql.length())));
            
            // 特殊处理IN子句 - 使用更精确的正则表达式匹配 IN 子句
            Pattern inPattern = Pattern.compile("(?i)\\s+in\\s*\\(\\s*\\?\\s*\\)");
            if (inPattern.matcher(sql).find()) {
                LOG.info("Detected IN clause in SQL, using specialized handler");
                
                // 如果是批量参数格式，并且参数值个数多于1，那么这是一个真正的IN查询
                // 否则，这只是一个普通参数，恰好SQL语句中使用了IN子句
                if (isBatchParameters && paramValues.size() > 1) {
                    LOG.info("Processing as IN clause with multiple values: " + paramValues.size() + " values");
                    return handleInClause(sql, paramValues, paramTypes);
                } else {
                    LOG.info("IN clause detected but only one parameter value, treating as regular replacement");
                    return replaceQuestionMarks(sql, paramValues, paramTypes);
                }
            } else {
                // 标准SQL参数替换
                LOG.info("Using standard parameter replacement");
                return replaceQuestionMarks(sql, paramValues, paramTypes);
            }
        } catch (Exception e) {
            LOG.error("Error generating complete SQL: " + sql + " with params: " + params, e);
            return sql + " /* Error replacing parameters: " + params + " */";
        }
    }
    
    /**
     * 特殊处理IN子句的参数替换
     */
    private static String handleInClause(String sql, List<String> paramValues, List<String> paramTypes) {
        // IN子句可能只有一个?占位符，但实际上代表多个参数
        // 示例: WHERE id IN (?)
        Pattern inPattern = Pattern.compile("(\\s+[Ii][Nn]\\s*\\(\\s*\\?\\s*\\))");
        Matcher inMatcher = inPattern.matcher(sql);
        
        if (inMatcher.find()) {
            LOG.info("Found IN clause at position: " + inMatcher.start() + "-" + inMatcher.end());
            LOG.info("Parameter values count: " + paramValues.size());
            
            // 如果只有一个参数值，就采用常规替换方式，而不是IN列表替换
            if (paramValues.size() == 1) {
                LOG.info("Single parameter value detected, using standard replacement for IN clause");
                String paramValue = paramValues.get(0);
                String paramType = paramTypes.get(0);
                String formattedValue = formatParamValue(paramValue, paramType);
                
                // 构建 IN 子句表达式（但只有一个值）
                String inClauseExpr = " IN (" + formattedValue + ")";
                
                // 替换IN子句，保留字段名
                String prefix = sql.substring(0, sql.toLowerCase().indexOf(" in "));
                String suffix = sql.substring(inMatcher.end());
                
                LOG.info("Replacing IN clause with single value: " + inClauseExpr);
                
                return prefix + inClauseExpr + suffix;
            }
            
            // 构建IN子句的参数列表（多个值）
            StringBuilder inParams = new StringBuilder(" IN (");
            for (int i = 0; i < paramValues.size(); i++) {
                if (i > 0) {
                    inParams.append(", ");
                }
                inParams.append(formatParamValue(paramValues.get(i), paramTypes.get(i)));
            }
            inParams.append(")");
            
            // 替换IN子句，保留字段名
            String prefix = sql.substring(0, sql.toLowerCase().indexOf(" in "));
            String suffix = sql.substring(inMatcher.end());
            
            LOG.info("Replacing IN clause: prefix=" + prefix + ", suffix=" + suffix);
            LOG.info("New IN params: " + inParams.toString());
            
            return prefix + inParams.toString() + suffix;
        } else {
            // 如果不是简单的IN(?)模式，就尝试更通用的匹配
            Pattern generalInPattern = Pattern.compile("([\\w`.]+)\\s+[Ii][Nn]\\s*\\(\\s*\\?\\s*\\)");
            Matcher generalMatcher = generalInPattern.matcher(sql);
            
            if (generalMatcher.find()) {
                LOG.info("Found general IN clause with field: " + generalMatcher.group(1));
                
                String fieldName = generalMatcher.group(1);
                
                // 如果只有一个参数值，就采用常规替换方式
                if (paramValues.size() == 1) {
                    LOG.info("Single parameter value detected, using standard replacement for general IN clause");
                    String paramValue = paramValues.get(0);
                    String paramType = paramTypes.get(0);
                    String formattedValue = formatParamValue(paramValue, paramType);
                    
                    // 构建 IN 子句表达式（但只有一个值）
                    String inClauseExpr = " IN (" + formattedValue + ")";
                    
                    // 在字段名后面替换 IN (?) 部分
                    int startPos = sql.indexOf(fieldName) + fieldName.length();
                    int endPos = sql.indexOf(")", startPos) + 1;
                    
                    String prefix = sql.substring(0, startPos);
                    String suffix = sql.substring(endPos);
                    
                    LOG.info("Replacing general IN clause with single value: " + inClauseExpr);
                    
                    return prefix + inClauseExpr + suffix;
                }
                
                // 构建IN子句的参数列表（多个值）
                StringBuilder inParams = new StringBuilder(" IN (");
                for (int i = 0; i < paramValues.size(); i++) {
                    if (i > 0) {
                        inParams.append(", ");
                    }
                    inParams.append(formatParamValue(paramValues.get(i), paramTypes.get(i)));
                }
                inParams.append(")");
                
                // 在字段名后面替换 IN (?) 部分
                int startPos = sql.indexOf(fieldName) + fieldName.length();
                int endPos = sql.indexOf(")", startPos) + 1;
                
                String prefix = sql.substring(0, startPos);
                String suffix = sql.substring(endPos);
                
                return prefix + inParams.toString() + suffix;
            }
            
            // 如果不是任何已知的IN模式，就使用标准替换
            return replaceQuestionMarks(sql, paramValues, paramTypes);
        }
    }
    
    /**
     * 替换SQL中的所有问号占位符
     */
    private static String replaceQuestionMarks(String sql, List<String> paramValues, List<String> paramTypes) {
        try {
            LOG.info("Replacing question marks in SQL: " + sql.substring(0, Math.min(50, sql.length())));
            LOG.info("With " + paramValues.size() + " parameters");
            
            StringBuilder result = new StringBuilder(sql);
            int paramIndex = 0;
            int questionMarkPos = 0;
            
            while ((questionMarkPos = result.indexOf("?", questionMarkPos)) != -1 && paramIndex < paramValues.size()) {
                String paramValue = paramValues.get(paramIndex);
                String paramType = paramTypes.get(paramIndex);
                String formattedValue = formatParamValue(paramValue, paramType);
                
                LOG.info("Replacing parameter #" + (paramIndex + 1) + ": ? -> " + formattedValue);
                
                // 替换问号
                result.replace(questionMarkPos, questionMarkPos + 1, formattedValue);
                
                // 更新位置
                questionMarkPos += formattedValue.length();
                paramIndex++;
            }
            
            // 检查是否所有参数都被使用
            if (paramIndex < paramValues.size()) {
                LOG.warn("Not all parameters used: " + (paramValues.size() - paramIndex) + " parameters remaining");
            }
            
            // 检查是否还有未替换的问号
            if (result.indexOf("?") != -1) {
                LOG.warn("SQL still contains ? placeholders but no more parameters available");
            }
            
            return result.toString();
        } catch (Exception e) {
            LOG.error("Error in replaceQuestionMarks", e);
            return sql + " /* Error replacing parameters */";
        }
    }

    private static String formatParamValue(String paramValue, String paramType) {
        if (paramValue == null || paramValue.equalsIgnoreCase("null")) return "NULL";
        
        // 如果参数值已经包含括号中的类型信息，先去除
        paramValue = removeParamTypeFromValue(paramValue);
        
        if (paramType != null) {
            switch (paramType.toUpperCase()) {
                case "STRING":
                case "VARCHAR":
                case "CHAR":
                case "TEXT":
                case "LONGVARCHAR":
                    return "'" + escapeSingleQuotes(paramValue) + "'";
                    
                case "TIMESTAMP":
                case "DATE":
                case "TIME":
                    return "'" + paramValue + "'";
                    
                case "INTEGER":
                case "BIGINT":
                case "SMALLINT":
                case "TINYINT":
                case "INT":
                case "LONG":
                case "SHORT":
                case "DECIMAL":
                case "NUMERIC":
                case "DOUBLE":
                case "FLOAT":
                case "REAL":
                    return paramValue;
                    
                case "BOOLEAN":
                case "BIT":
                    return paramValue;
                    
                default:
                    // 如果类型不明确，根据值的格式推断
                    return inferFormattedValue(paramValue);
            }
        } else {
            return inferFormattedValue(paramValue);
        }
    }
    
    /**
     * 去除参数值中的类型信息
     * 例如: "value(TYPE)" -> "value"
     */
    private static String removeParamTypeFromValue(String paramValue) {
        if (paramValue == null) return null;
        
        Pattern typePattern = Pattern.compile("(.+)\\(([A-Za-z]+)\\)");
        Matcher matcher = typePattern.matcher(paramValue);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        return paramValue;
    }
    
    /**
     * 转义SQL字符串中的单引号
     */
    private static String escapeSingleQuotes(String value) {
        if (value == null) return "";
        // 如果值已经包含引号，不做处理
        if (value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value.replace("'", "''");
    }
    
    private static String inferFormattedValue(String paramValue) {
        if (paramValue == null) return "NULL";
        
        // 清除可能存在的参数类型标记
        paramValue = removeParamTypeFromValue(paramValue);
        
        if (paramValue.equalsIgnoreCase("null")) {
            return "NULL";
        } else if (paramValue.matches("^-?\\d+$") || 
                   paramValue.matches("^-?\\d+\\.\\d+$") ||
                   paramValue.equalsIgnoreCase("true") || 
                   paramValue.equalsIgnoreCase("false")) {
            return paramValue;
        }
        
        // 如果值已经包含单引号，则不再添加
        if (paramValue.startsWith("'") && paramValue.endsWith("'")) {
            return paramValue;
        }
        
        return "'" + escapeSingleQuotes(paramValue) + "'";
    }
} 