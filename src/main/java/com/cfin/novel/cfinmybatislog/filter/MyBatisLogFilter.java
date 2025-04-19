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

public class MyBatisLogFilter implements Filter {
    private static final Logger LOG = Logger.getInstance(MyBatisLogFilter.class);
    
    // 扩展匹配模式以支持更多 MyBatis 日志格式
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

    // 使用实例变量存储状态
    private String lastSql;
    private String lastParams;
    private String lastTime;
    private final Project project;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    public MyBatisLogFilter(Project project) {
        this.project = project;
        LOG.info("MyBatisLogFilter initialized for project: " + project.getName());
    }

    @Nullable
    @Override
    public Result applyFilter(@NotNull String line, int entireLength) {
        if (isProcessing.get()) {
            return null;  // 防止重入和死锁
        }
        
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        
        try {
            if (isProcessing.compareAndSet(false, true)) {
                processLine(line);
            }
        } finally {
            isProcessing.set(false);
        }
        
        // 返回 null 表示不进行高亮或其他处理
        return null;
    }
    
    private void processLine(String line) {
        try {
            LOG.debug("Filtering line: " + line);
            
            // 尝试匹配标准 MyBatis 日志格式
            if (SQL_PATTERN.matcher(line).find() || SPRING_BOOT_SQL_PATTERN.matcher(line).find()) {
                LOG.info("Matched SQL pattern: " + line);
                
                // 使用UI线程安全方式处理
                ApplicationManager.getApplication().invokeLater(() -> {
                    MyBatisLogManager manager = MyBatisLogManager.getInstance(project);
                    
                    if (CLEAR_SQL_PATTERN.matcher(line).find() || SPRING_BOOT_SQL.matcher(line).find()) {
                        // 处理 SQL 语句
                        String sql = extractSql(line);
                        if (sql != null && !sql.isEmpty()) {
                            lastSql = sql;
                            manager.addLog("SQL: " + sql);
                            LOG.info("Extracted SQL: " + sql);
                        }
                    } else if (PARAMETERS_PATTERN.matcher(line).find() || SPRING_BOOT_PARAMS.matcher(line).find()) {
                        // 处理参数
                        String params = extractParams(line);
                        if (params != null && !params.isEmpty()) {
                            lastParams = params;
                            String formattedParams = formatParameters(params);
                            manager.addLog("Parameters: " + formattedParams);
                            LOG.info("Extracted Parameters: " + formattedParams);
    
                            // 生成并显示完整 SQL
                            if (lastSql != null && !lastParams.isEmpty()) {
                                String completeSql = generateCompleteSql(lastSql, lastParams);
                                manager.addLog("Complete SQL: " + completeSql);
                                LOG.info("Generated Complete SQL: " + completeSql);
                            }
    
                            // 显示执行时间
                            String time = lastTime != null ? lastTime : "0";
                            String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
                            manager.addLog("Time: " + time + "ms (" + timestamp + ")");
    
                            manager.addLog("----------------------------------------");
                        }
                    }
                });
            } else if (TIME_PATTERN.matcher(line).find()) {
                // 捕获执行时间
                Matcher matcher = TIME_PATTERN.matcher(line);
                if (matcher.find()) {
                    for (int i = 1; i <= matcher.groupCount(); i++) {
                        if (matcher.group(i) != null) {
                            lastTime = matcher.group(i);
                            LOG.info("Extracted Time: " + lastTime + "ms");
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error processing line: " + line, e);
        }
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