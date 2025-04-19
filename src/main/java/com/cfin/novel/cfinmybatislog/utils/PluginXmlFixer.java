package com.cfin.novel.cfinmybatislog.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * 工具类，用于修复 plugin.xml 中的标签错误
 * 将 <n> 标签修改为 <name> 标签
 */
public class PluginXmlFixer {
    public static void main(String[] args) {
        try {
            // 获取当前工作目录
            Path workDir = Paths.get(System.getProperty("user.dir"));
            
            // 定位 plugin.xml 文件
            Path pluginXmlPath = workDir.resolve("src/main/resources/META-INF/plugin.xml");
            
            if (Files.exists(pluginXmlPath)) {
                // 读取文件内容
                String content = new String(Files.readAllBytes(pluginXmlPath), StandardCharsets.UTF_8);
                
                // 替换标签 <n> 为 <name>，</n> 为 </name>
                content = content.replace("<n>", "<name>");
                content = content.replace("</n>", "</name>");
                
                // 写回文件
                Files.write(pluginXmlPath, content.getBytes(StandardCharsets.UTF_8));
                
                System.out.println("plugin.xml 文件已成功修复!");
            } else {
                System.err.println("找不到文件: " + pluginXmlPath);
            }
        } catch (Exception e) {
            System.err.println("修复过程中出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
} 