# MyBatis SQL Logger Plugin

一个用于显示 MyBatis 和 MyBatis-Plus SQL 执行日志的 IntelliJ IDEA 插件。

## 功能

- 实时 SQL 日志显示
- 参数类型和值展示
- 完整 SQL 生成
- 执行时间跟踪
- SQL 过滤功能
- 复制 SQL 功能

## 使用说明

1. 安装插件后，通过 Tools > MyBatis Logger 打开日志窗口
2. 运行 Spring Boot 应用程序
3. 查看 MyBatis Logger 工具窗口中显示的 SQL 日志
4. 可以通过搜索框过滤特定表的 SQL 操作
5. 可以通过右键菜单复制完整 SQL

## Spring Boot 配置

要使插件正确捕获 MyBatis 的 SQL 日志，您需要在 Spring Boot 应用程序中进行以下配置：

### 1. 添加日志配置

在 `application.properties` 或 `application.yml` 文件中添加以下配置：

```properties
# application.properties 示例
# 显示 SQL 语句
logging.level.org.mybatis=DEBUG
logging.level.java.sql=DEBUG
logging.level.java.sql.Statement=DEBUG
logging.level.java.sql.PreparedStatement=DEBUG
logging.level.java.sql.ResultSet=DEBUG

# 针对特定的 Mapper 接口显示 SQL 语句
logging.level.com.your.package.mapper=DEBUG

# MyBatis 的 SQL 日志输出格式
# 这将确保日志包含参数值
mybatis.configuration.log-impl=org.apache.ibatis.logging.stdout.StdOutImpl
```

如果使用的是 YAML 格式，可以这样配置：

```yaml
# application.yml 示例
logging:
  level:
    org.mybatis: DEBUG
    java.sql: DEBUG
    java.sql.Statement: DEBUG
    java.sql.PreparedStatement: DEBUG
    java.sql.ResultSet: DEBUG
    com.your.package.mapper: DEBUG

mybatis:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
```

### 2. 如果使用 MyBatis-Plus

如果您的项目使用 MyBatis-Plus，请添加以下配置：

```properties
# application.properties 示例
# 显示 SQL 语句
mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.stdout.StdOutImpl
```

或 YAML 格式：

```yaml
# application.yml 示例
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
```

### 3. 配置 Logback

如果您使用 Logback 作为日志系统（Spring Boot 默认使用），可以创建或修改 `logback-spring.xml` 文件，放在 `src/main/resources` 目录下：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/base.xml"/>
    
    <!-- MyBatis 日志配置 -->
    <logger name="org.mybatis" level="DEBUG"/>
    <logger name="java.sql" level="DEBUG"/>
    <logger name="java.sql.Statement" level="DEBUG"/>
    <logger name="java.sql.PreparedStatement" level="DEBUG"/>
    <logger name="java.sql.ResultSet" level="DEBUG"/>
    
    <!-- 您的 Mapper 接口包 -->
    <logger name="com.your.package.mapper" level="DEBUG"/>
</configuration>
```

## 注意事项

- 确保您的应用程序使用的 MyBatis 版本在 3.4.0 及以上
- 日志级别设置为 DEBUG 可能会影响应用程序性能，仅在开发环境中使用
- 某些数据库驱动可能需要额外的配置来启用详细日志 