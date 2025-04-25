package com.cfin.novel.cfinmybatislog.filter;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.project.Project;
import com.cfin.novel.cfinmybatislog.manager.MyBatisLogManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.UUID;

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
    private static final Pattern SPRING_BOOT_SQL_PATTERN = Pattern.compile("(?i)(\\[\\s*\\w+\\s*\\]\\s*DEBUG\\s*.*?Preparing:|\\[\\s*\\w+\\s*\\]\\s*DEBUG\\s*.*?Parameters:|.*?DEBUG.*?Preparing:|.*?DEBUG.*?Parameters:)");
    private static final Pattern SPRING_BOOT_SQL = Pattern.compile("(?i)(\\[\\s*\\w+\\s*\\]\\s*DEBUG\\s*.*?Preparing:|.*?DEBUG.*?Preparing:)");
    private static final Pattern SPRING_BOOT_PARAMS = Pattern.compile("(?i)(\\[\\s*\\w+\\s*\\]\\s*DEBUG\\s*.*?Parameters:|.*?DEBUG.*?Parameters:)");

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

        // 快速预检查，避免昂贵的正则表达式匹配
        boolean mightBeSqlLog = line.contains("SQL") ||
                               line.contains("Preparing") ||
                               line.contains("Parameters") ||
                               line.contains("mybatis") ||
                               line.contains("Executed") ||
                               line.contains("Total:");

        if (!mightBeSqlLog) {
            return null;  // 快速丢弃明显不是SQL日志的行
        }

        // 使用正则表达式进行更精确的匹配
        boolean isSqlLog = SQL_PATTERN.matcher(line).find() ||
                         SPRING_BOOT_SQL_PATTERN.matcher(line).find() ||
                         TIME_PATTERN.matcher(line).find();

        if (!isSqlLog) {
            return null;  // 不是SQL日志，丢弃
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
                return;
            }
            
            // 处理SQL语句
            if (CLEAR_SQL_PATTERN.matcher(line).find() || SPRING_BOOT_SQL.matcher(line).find()) {
                String sql = extractSql(line);
                if (sql != null && !sql.isEmpty()) {
                    // 创建新的SQL执行记录
                    SqlExecution execution = new SqlExecution(UUID.randomUUID().toString());
                    execution.sql = sql;
                    pendingSqlExecutions.offer(execution);
                    manager.addLog("SQL: " + sql);
                }
            } 
            // 处理参数
            else if (PARAMETERS_PATTERN.matcher(line).find() || SPRING_BOOT_PARAMS.matcher(line).find()) {
                String params = extractParams(line);
                if (params != null && !params.isEmpty()) {
                    // 寻找匹配的SQL执行记录
                    SqlExecution latestExecution = findOrCreateMatchingSqlExecution();
                    if (latestExecution != null) {
                        latestExecution.params = params;
                        String formattedParams = formatParameters(params);
                        manager.addLog("Parameters: " + formattedParams);
                        
                        // 生成并显示完整SQL
                        if (latestExecution.sql != null) {
                            String completeSql = generateCompleteSql(latestExecution.sql, params);
                            manager.addLog("Complete SQL: " + completeSql);
                        }
                        
                        // 显示执行时间
                        String time = latestExecution.time != null ? latestExecution.time : "0";
                        String timestamp = latestExecution.timestamp.format(DATE_FORMATTER);
                        manager.addLog("Time: " + time + "ms (" + timestamp + ")");
                        manager.addLog("----------------------------------------");
                        
                        // 处理完成后检查是否可以从队列中移除
                        if (latestExecution.isComplete()) {
                            pendingSqlExecutions.remove(latestExecution);
                        }
                    }
                }
            } 
            // 捕获执行时间
            else if (TIME_PATTERN.matcher(line).find()) {
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
                        // 更新最近的执行记录
                        SqlExecution latestExecution = findOrCreateMatchingSqlExecution();
                        if (latestExecution != null) {
                            latestExecution.time = time;
                        }
                    }
                }
            }
            
            // 清理过期的SQL执行记录
            cleanupOldExecutions();
            
        } catch (Exception e) {
            LOG.error("Error processing line: " + line, e);
        }
    }
    
    /**
     * 查找匹配的SQL执行记录，如果没有找到则创建一个新的
     */
    private SqlExecution findOrCreateMatchingSqlExecution() {
        LocalDateTime now = LocalDateTime.now();
        
        // 首先尝试找到最近的未完成的SQL执行记录
        for (SqlExecution execution : pendingSqlExecutions) {
            if (!execution.isComplete() && 
                java.time.Duration.between(execution.timestamp, now).toMillis() < SQL_GROUP_TIME_THRESHOLD_MS) {
                return execution;
            }
        }
        
        // 如果没有找到匹配的，创建一个新的
        SqlExecution newExecution = new SqlExecution(UUID.randomUUID().toString());
        pendingSqlExecutions.offer(newExecution);
        return newExecution;
    }
    
    /**
     * 清理过期的SQL执行记录
     */
    private void cleanupOldExecutions() {
        LocalDateTime now = LocalDateTime.now();
        pendingSqlExecutions.removeIf(execution -> 
            java.time.Duration.between(execution.timestamp, now).toMillis() > SQL_GROUP_TIME_THRESHOLD_MS * 10);
    }

    private String extractSql(String text) {
        try {
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
                return text.substring(index).trim();
            }
            
            // 如果以上匹配都没成功，但确实包含SQL关键字
            if (text.contains("SELECT") || text.contains("UPDATE") || 
                text.contains("INSERT") || text.contains("DELETE") || 
                text.contains("select") || text.contains("update") || 
                text.contains("insert") || text.contains("delete")) {
                return text.trim();
            }
            
            return text.trim();
        } catch (Exception e) {
            LOG.error("Error extracting SQL: " + text, e);
            return text.trim();
        }
    }

    private String extractParams(String text) {
        try {
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
                return text.substring(index).trim();
            }
            
            // 如果找不到正式的参数标记，尝试直接查找参数
            Matcher matcher = SIMPLE_PARAM_PATTERN.matcher(text);
            if (matcher.find()) {
                return text.trim();
            }
            
            return text.trim();
        } catch (Exception e) {
            LOG.error("Error extracting parameters: " + text, e);
            return text.trim();
        }
    }

    private String formatParameters(String params) {
        if (params == null || params.isEmpty()) return "[]";
        
        try {
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
                }
            }
            
            formattedParams.append("]");
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
            Matcher matcher = PARAM_VALUE_PATTERN.matcher(params);
            String resultSql = sql;
            int offset = 0;
            boolean paramFound = false;
            
            while (matcher.find()) {
                paramFound = true;
                String paramType = matcher.group(1);
                String paramValue = matcher.group(2).trim();
                int questionMarkIndex = resultSql.indexOf("?", offset);
                
                if (questionMarkIndex == -1) break;
                
                String formattedValue = formatParamValue(paramValue, paramType);
                resultSql = resultSql.substring(0, questionMarkIndex) + formattedValue + 
                           resultSql.substring(questionMarkIndex + 1);
                offset = questionMarkIndex + formattedValue.length();
            }
            
            // 如果没找到带类型的参数，尝试简单参数格式
            if (!paramFound) {
                String[] simpleParams = params.split(",");
                resultSql = sql;
                offset = 0;
                
                for (String value : simpleParams) {
                    value = value.trim();
                    if (value.isEmpty()) continue;
                    
                    int questionMarkIndex = resultSql.indexOf("?", offset);
                    if (questionMarkIndex == -1) break;
                    
                    String paramType = getParamType(value);
                    String formattedValue = formatParamValue(value, paramType);
                    resultSql = resultSql.substring(0, questionMarkIndex) + formattedValue + 
                               resultSql.substring(questionMarkIndex + 1);
                    offset = questionMarkIndex + formattedValue.length();
                }
            }
            
            return resultSql;
        } catch (Exception e) {
            LOG.error("Error generating complete SQL: " + sql + " with params: " + params, e);
            return sql + " /* Error replacing parameters: " + params + " */";
        }
    }

    private static String formatParamValue(String paramValue, String paramType) {
        if (paramValue == null || paramValue.equalsIgnoreCase("null")) return "NULL";
        
        if (paramType != null) {
            switch (paramType.toUpperCase()) {
                case "STRING":
                case "VARCHAR":
                case "CHAR":
                case "TEXT":
                case "LONGVARCHAR":
                    return "'" + paramValue + "'";
                    
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
    
    private static String inferFormattedValue(String paramValue) {
        if (paramValue.matches("^-?\\d+$") || 
            paramValue.matches("^-?\\d+\\.\\d+$") ||
            paramValue.equalsIgnoreCase("true") || 
            paramValue.equalsIgnoreCase("false")) {
            return paramValue;
        }
        
        // 如果值已经包含单引号，则不再添加
        if (paramValue.startsWith("'") && paramValue.endsWith("'")) {
            return paramValue;
        }
        
        return "'" + paramValue + "'";
    }
} 