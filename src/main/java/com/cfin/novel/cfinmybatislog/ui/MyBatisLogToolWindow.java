package com.cfin.novel.cfinmybatislog.ui;

import com.cfin.novel.cfinmybatislog.manager.MyBatisLogManager;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.SearchTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.util.ui.UIUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.diagnostic.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.ActionEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.prefs.Preferences;
import java.util.concurrent.atomic.AtomicBoolean;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.SearchTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.KeyStroke;

public class MyBatisLogToolWindow implements ToolWindowFactory, DumbAware {
    private JTextPane logTextPane;
    private Project project;
    private JLabel statusLabel;
    private static final String FONT_SIZE_PREF_KEY = "cfin.mybatis.log.font.size";
    private static final int DEFAULT_FONT_SIZE = 12;
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 24;
    
    // 延迟初始化配置
    private static final boolean USE_LAZY_INIT = true;
    private static final int LAZY_INIT_DELAY_MS = 500;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isAppStartupComplete = new AtomicBoolean(false);
    
    // 定义主题色
    private static final Color PRIMARY_COLOR = new JBColor(new Color(0, 120, 212), new Color(75, 110, 175));
    private static final Color SECONDARY_COLOR = new JBColor(new Color(242, 242, 242), new Color(60, 63, 65));
    private static final Color ACCENT_COLOR = new JBColor(new Color(0, 153, 153), new Color(0, 153, 153));
    
    // 按钮悬停颜色
    private static final Color BTN_HOVER_COLOR = new JBColor(new Color(230, 230, 230), new Color(75, 75, 75));

    private static final Logger LOG = Logger.getInstance(MyBatisLogToolWindow.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        this.project = project;
        
        LOG.info("Creating MyBatis Log Tool Window content for project: " + project.getName());
        
        // 创建一个简单的加载中界面
        JPanel loadingPanel = new JPanel(new BorderLayout());
        JLabel loadingLabel = new JLabel("Loading MyBatis Logger...", SwingConstants.CENTER);
        loadingLabel.setFont(loadingLabel.getFont().deriveFont(Font.PLAIN, 14f));
        loadingPanel.add(loadingLabel, BorderLayout.CENTER);
        
        // 先显示加载中界面
        ContentFactory contentFactory = ContentFactory.getInstance();
        toolWindow.getContentManager().addContent(contentFactory.createContent(loadingPanel, "", false));
        LOG.info("Added loading panel to tool window");
        
        // 先启动日志管理器，确保日志不会丢失
        MyBatisLogManager logManager = MyBatisLogManager.getInstance(project);
        logManager.setEnabled(true);
        LOG.info("Enabled MyBatis Log Manager");
        
        // 在应用程序完全启动后初始化
        ApplicationManager.getApplication().invokeLater(() -> {
            isAppStartupComplete.set(true);
            LOG.info("Application startup complete flag set to true");
            
            // 如果使用延迟初始化，则等待额外的延迟时间
            if (USE_LAZY_INIT) {
                LOG.info("Using lazy initialization with delay: " + LAZY_INIT_DELAY_MS + "ms");
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        // 延迟一段时间，确保IDE已完全启动
                        Thread.sleep(LAZY_INIT_DELAY_MS);
                        LOG.info("Lazy initialization delay completed");
                        
                        // 在EDT线程中执行UI初始化
                        SwingUtilities.invokeLater(() -> {
                            LOG.info("Initializing UI after startup delay");
                            initializeUiAfterStartup(toolWindow, contentFactory);
                        });
                    } catch (InterruptedException e) {
                        LOG.error("Lazy initialization was interrupted", e);
                        Thread.currentThread().interrupt();
                    }
                });
            } else {
                // 不使用额外延迟时间，但仍然等待应用程序完全启动
                LOG.info("Initializing UI immediately after application startup");
                SwingUtilities.invokeLater(() -> {
                    initializeUiAfterStartup(toolWindow, contentFactory);
                });
            }
        }, project.getDisposed());
    }
    
    /**
     * 在应用程序完全启动后初始化UI
     */
    private void initializeUiAfterStartup(ToolWindow toolWindow, ContentFactory contentFactory) {
        LOG.info("Beginning UI initialization after startup");
        
        // 移除加载界面
        toolWindow.getContentManager().removeAllContents(true);
        LOG.info("Removed loading panel");
        
        // 创建实际内容
        LOG.info("Creating main panel");
        JPanel mainPanel = createMainPanel();
        
        // 添加到工具窗口
        LOG.info("Adding main panel to tool window");
        toolWindow.getContentManager().addContent(
            contentFactory.createContent(mainPanel, "", false));
        
        // 初始化日志管理器
        LOG.info("Initializing log manager");
        initializeLogManager();
        
        isInitialized.set(true);
        LOG.info("UI initialization completed successfully");
        
        showStatusMessage("MyBatis Logger ready - application startup complete");
    }
    
    private JPanel createMainPanel() {
        // 创建主面板，使用现代化的布局
        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBorder(JBUI.Borders.empty(8));
        
        // 创建卡片式顶部面板
        JPanel topCard = createCardPanel();
        topCard.setLayout(new BorderLayout());
        topCard.setBorder(JBUI.Borders.empty(12, 15, 12, 15));
        
        // 字体大小控制面板
        JPanel fontPanel = createFontControlPanel();
        
        // 搜索面板
        JPanel searchPanel = createSearchPanel();
        
        // 按钮面板
        JPanel buttonPanel = createButtonPanel();
        
        // 组装顶部面板
        JPanel controlPanel = new JPanel(new BorderLayout(15, 0));
        controlPanel.setOpaque(false);
        controlPanel.add(fontPanel, BorderLayout.WEST);
        controlPanel.add(searchPanel, BorderLayout.CENTER);
        controlPanel.add(buttonPanel, BorderLayout.EAST);
        
        topCard.add(controlPanel, BorderLayout.CENTER);
        mainPanel.add(topCard, BorderLayout.NORTH);
        
        // 创建日志展示区域（卡片式设计）
        JPanel logCard = createCardPanel();
        logCard.setLayout(new BorderLayout());
        
        // 创建文本窗格用于显示SQL日志
        logTextPane = createStyledTextPane();
        
        // 添加带滚动条的日志面板
        JBScrollPane scrollPane = createStyledScrollPane(logTextPane);
        logCard.add(scrollPane, BorderLayout.CENTER);
        
        mainPanel.add(logCard, BorderLayout.CENTER);
        
        // 创建底部状态栏（卡片式设计）
        JPanel statusCard = createCardPanel();
        statusCard.setLayout(new BorderLayout());
        statusCard.setBorder(JBUI.Borders.empty(8, 15));
        
        statusLabel = new JLabel("MyBatis SQL Logger Ready");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        statusLabel.setForeground(UIUtil.getContextHelpForeground());
        statusCard.add(statusLabel, BorderLayout.WEST);
        
        mainPanel.add(statusCard, BorderLayout.SOUTH);
        
        return mainPanel;
    }
    
    private void initializeLogManager() {
        // 获取日志管理器实例并初始化
        MyBatisLogManager logManager = MyBatisLogManager.getInstance(project);
        logManager.setTextPane(logTextPane);
        
        // 立即启用日志处理，不使用延迟
        logManager.setEnabled(true);
        showStatusMessage("MyBatis SQL Logger is now active and capturing logs");
        
        // 清空现有的"等待初始化"消息
        try {
            StyledDocument doc = logTextPane.getStyledDocument();
            doc.remove(0, doc.getLength());
        } catch (Exception e) {
            // 忽略可能的异常
        }
    }
    
    /**
     * 创建卡片式面板，带有轻微阴影和圆角
     */
    private JPanel createCardPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(UIUtil.getPanelBackground());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border().darker(), 1),
            JBUI.Borders.empty(2)
        ));
        return panel;
    }
    
    /**
     * 创建样式化的文本窗格
     */
    private JTextPane createStyledTextPane() {
        JTextPane textPane = new JTextPane();
        textPane.setEditable(false);
        
        // 获取IDE当前编辑器字体
        Font editorFont = UIManager.getFont("Editor.font");
        String fontFamily = editorFont != null ? editorFont.getFamily() : 
                           "JetBrains Mono".equals(UIManager.get("Editor.font.name")) ? 
                           "JetBrains Mono" : Font.MONOSPACED;
        
        // 设置初始字体和大小
        int fontSize = getCurrentFontSize();
        textPane.setFont(new Font(fontFamily, Font.PLAIN, fontSize));
        
        // 增加边距，使文本与边缘有适当间距
        textPane.setBorder(JBUI.Borders.empty(10));
        
        // 设置背景色以匹配编辑器背景（自动适应深色/浅色主题）
        textPane.setBackground(UIUtil.getTextFieldBackground());
        
        // 禁用默认的复制行为，使用自定义的复制处理
        textPane.getActionMap().put(DefaultEditorKit.copyAction, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String selectedText = textPane.getSelectedText();
                if (selectedText != null && !selectedText.isEmpty()) {
                    CopyPasteManager.getInstance().setContents(new StringSelection(selectedText));
                    showStatusMessage("SQL copied to clipboard");
                }
            }
        });
        
        return textPane;
    }
    
    /**
     * 创建样式化的滚动面板
     */
    private JBScrollPane createStyledScrollPane(JComponent component) {
        JBScrollPane scrollPane = new JBScrollPane(component);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
    }
    
    /**
     * 创建字体控制面板
     */
    private JPanel createFontControlPanel() {
        JPanel fontPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        fontPanel.setOpaque(false);
        
        // 字体大小标签
        JBLabel fontLabel = new JBLabel("Font Size");
        fontLabel.setForeground(UIUtil.getLabelForeground());
        
        // 字体大小减小按钮
        JButton decreaseFontButton = createStyledButton("", AllIcons.General.Remove, "Decrease font size");
        decreaseFontButton.addActionListener(e -> {
            changeFontSize(-1);
            // 添加日志，帮助诊断
            LOG.info("Decreased font size to " + getCurrentFontSize());
        });
        
        // 字体大小显示
        JLabel fontSizeLabel = new JLabel(String.valueOf(getCurrentFontSize()));
        fontSizeLabel.setName("fontSizeLabel"); // 添加一个唯一的名称用于查找
        fontSizeLabel.setForeground(UIUtil.getLabelForeground());
        fontSizeLabel.setBorder(JBUI.Borders.empty(0, 5));
        
        // 字体大小增加按钮
        JButton increaseFontButton = createStyledButton("", AllIcons.General.Add, "Increase font size");
        increaseFontButton.addActionListener(e -> {
            changeFontSize(1);
            // 添加日志，帮助诊断
            LOG.info("Increased font size to " + getCurrentFontSize());
        });
        
        // 添加到字体面板
        fontPanel.add(fontLabel);
        fontPanel.add(decreaseFontButton);
        fontPanel.add(fontSizeLabel);
        fontPanel.add(increaseFontButton);
        
        return fontPanel;
    }
    
    /**
     * 创建搜索面板
     */
    private JPanel createSearchPanel() {
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setOpaque(false);
        
        // 创建搜索图标
        JLabel searchIcon = new JLabel(AllIcons.Actions.Find);
        searchIcon.setBorder(JBUI.Borders.empty(0, 0, 0, 5));
        
        // 创建现代化的搜索框 - 减少宽度，使其更加美观
        JBTextField searchField = new JBTextField(15);  // 从20减少到15
        searchField.setBorder(JBUI.Borders.empty(4));   // 从6减少到4，减少高度
        searchField.putClientProperty("JTextField.placeholderText", "Filter by table or SQL...");  // 缩短提示文本
        
        // 设置紧凑的圆角搜索框
        JPanel searchInputPanel = new JPanel(new BorderLayout());
        searchInputPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(1, 6)  // 从2,8减少到1,6，减少内边距
        ));
        searchInputPanel.add(searchIcon, BorderLayout.WEST);
        searchInputPanel.add(searchField, BorderLayout.CENTER);
        
        // 使用更紧凑的标签
        JBLabel filterLabel = new JBLabel("Filter:");
        filterLabel.setBorder(JBUI.Borders.emptyRight(4));  // 添加右边距确保不会太靠近搜索框
        
        searchPanel.add(filterLabel, BorderLayout.WEST);
        
        // 创建一个容器来限制搜索框的最大宽度
        JPanel searchFieldContainer = new JPanel(new BorderLayout());
        searchFieldContainer.setOpaque(false);
        searchFieldContainer.add(searchInputPanel, BorderLayout.CENTER);
        searchFieldContainer.setPreferredSize(new Dimension(280, searchInputPanel.getPreferredSize().height));  // 限制宽度
        
        searchPanel.add(searchFieldContainer, BorderLayout.CENTER);
        
        // 添加搜索监听器 - 确保DocumentListener能正确响应事件
        DocumentListener searchListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                try {
                    String searchText = searchField.getText();
                    LOG.info("Filter text updated: " + searchText);
                    filterLogs(searchText);
                } catch (Exception ex) {
                    LOG.error("Error handling insertUpdate: " + ex.getMessage(), ex);
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                try {
                    String searchText = searchField.getText();
                    LOG.info("Filter text updated: " + searchText);
                    filterLogs(searchText);
                } catch (Exception ex) {
                    LOG.error("Error handling removeUpdate: " + ex.getMessage(), ex);
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                try {
                    String searchText = searchField.getText();
                    LOG.info("Filter text updated: " + searchText);
                    filterLogs(searchText);
                } catch (Exception ex) {
                    LOG.error("Error handling changedUpdate: " + ex.getMessage(), ex);
                }
            }
        };
        
        // 移除旧的监听器，确保不会重复添加
        Document doc = searchField.getDocument();
        
        // 直接添加新的监听器，如果之前已有监听器，在添加前不需要手动移除
        // 由于我们总是创建新的SearchPanel，所以每次都是新的文档对象
        doc.addDocumentListener(searchListener);
        LOG.info("Added document listener to search field");
        
        return searchPanel;
    }
    
    /**
     * 创建按钮面板
     */
    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonPanel.setOpaque(false);
        
        // 复制选择的内容按钮
        JButton copyButton = createStyledButton("Copy Selected", AllIcons.Actions.Copy, "Copy selected text to clipboard");
        copyButton.addActionListener(e -> {
            String selectedText = logTextPane.getSelectedText();
            if (selectedText != null && !selectedText.isEmpty()) {
                CopyPasteManager.getInstance().setContents(new StringSelection(selectedText));
                showStatusMessage("Selected text copied to clipboard");
                LOG.info("Copied selected text to clipboard");
            } else {
                showStatusMessage("No text selected for copy");
                LOG.info("No text selected for copy");
            }
        });
        buttonPanel.add(copyButton);
        
        // 复制完整SQL按钮
        JButton copySqlButton = createStyledButton("Copy SQL", AllIcons.Vcs.History, "Copy the latest complete SQL statement");
        copySqlButton.addActionListener(e -> {
            String completeSql = findLatestCompleteSql();
            if (completeSql != null && !completeSql.isEmpty()) {
                completeSql = removeParamTypes(completeSql);
                CopyPasteManager.getInstance().setContents(new StringSelection(completeSql));
                showStatusMessage("Complete SQL copied to clipboard");
                LOG.info("Copied complete SQL to clipboard");
            } else {
                showStatusMessage("No SQL found to copy");
                LOG.info("No SQL found to copy");
            }
        });
        buttonPanel.add(copySqlButton);

        // 添加清空按钮
        JButton clearButton = createStyledButton("Clear", AllIcons.Actions.GC, "Clear all log entries");
        clearButton.addActionListener(e -> {
            MyBatisLogManager.getInstance(project).clearLogs();
            showStatusMessage("Logs cleared");
            LOG.info("Logs cleared");
        });
        buttonPanel.add(clearButton);
        
        // 添加重置UI按钮
        JButton resetButton = createStyledButton("Reset UI", AllIcons.Actions.Refresh, "Reset UI if filters or buttons stop working");
        resetButton.addActionListener(e -> {
            LOG.info("Reset UI button clicked");
            resetUI();
        });
        buttonPanel.add(resetButton);
        
        return buttonPanel;
    }
    
    /**
     * 创建样式化按钮
     */
    private JButton createStyledButton(String text, Icon icon, String tooltip) {
        JButton button = new JButton(text, icon);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setBorderPainted(true);
        button.setContentAreaFilled(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(5, 8)
        ));
        
        // 保存默认的按钮背景色
        final Color defaultBackground = button.getBackground();
        
        // 添加悬停效果
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setBackground(BTN_HOVER_COLOR);
            }

            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setBackground(defaultBackground);
            }
        });
        
        return button;
    }
    
    /**
     * 显示状态消息
     */
    private void showStatusMessage(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
            
            // 3秒后重置消息
            Timer timer = new Timer(3000, e -> {
                if (statusLabel != null) {
                    statusLabel.setText("MyBatis SQL Logger Ready");
                }
            });
            timer.setRepeats(false);
            timer.start();
        }
    }
    
    /**
     * 过滤日志并更新状态栏
     */
    private void filterLogs(String searchText) {
        try {
            String trimmedText = searchText.trim();
            LOG.info("Filtering logs with text: '" + trimmedText + "'");
            
            // 获取MyBatisLogManager实例
            MyBatisLogManager manager = MyBatisLogManager.getInstance(project);
            if (manager != null) {
                manager.filterLogs(trimmedText);
                updateStatusLabel(trimmedText);
            } else {
                LOG.error("Failed to get MyBatisLogManager instance for filtering");
            }
        } catch (Exception e) {
            LOG.error("Error filtering logs: " + e.getMessage(), e);
        }
    }
    
    /**
     * 更新状态栏信息
     */
    private void updateStatusLabel(String searchText) {
        if (statusLabel != null) {
            statusLabel.setText(searchText.isEmpty() 
                    ? "MyBatis SQL Logger Ready" 
                    : "Filtering: " + searchText);
        }
    }
    
    /**
     * 获取当前保存的字体大小或默认值
     */
    private int getCurrentFontSize() {
        Preferences prefs = Preferences.userNodeForPackage(MyBatisLogToolWindow.class);
        return prefs.getInt(FONT_SIZE_PREF_KEY, DEFAULT_FONT_SIZE);
    }
    
    /**
     * 改变字体大小
     * @param delta 字体大小变化量
     */
    private void changeFontSize(int delta) {
        try {
            Preferences prefs = Preferences.userNodeForPackage(MyBatisLogToolWindow.class);
            int currentSize = getCurrentFontSize();
            int newSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, currentSize + delta));
            
            if (newSize != currentSize) {
                LOG.info("Changing font size from " + currentSize + " to " + newSize);
                prefs.putInt(FONT_SIZE_PREF_KEY, newSize);
                updateFontSize(logTextPane);
                
                // 更新显示的字体大小标签
                Component labelComponent = findComponentByNameAndType("fontSizeLabel", JLabel.class);
                if (labelComponent != null && labelComponent instanceof JLabel) {
                    JLabel label = (JLabel) labelComponent;
                    label.setText(String.valueOf(newSize));
                    LOG.info("Updated font size label to " + newSize);
                    showStatusMessage("Font size changed to " + newSize);
                } else {
                    LOG.warn("Could not find font size label to update");
                    
                    // 尝试使用旧方法查找标签
                    Component parent = findFontSizeLabel();
                    if (parent != null && parent instanceof JLabel) {
                        ((JLabel) parent).setText(String.valueOf(newSize));
                        LOG.info("Updated font size label using backup method");
                        showStatusMessage("Font size changed to " + newSize);
                    } else {
                        LOG.warn("Both label finding methods failed");
                    }
                }
            } else {
                LOG.info("Font size not changed (current: " + currentSize + ")");
            }
        } catch (Exception e) {
            LOG.error("Error changing font size: " + e.getMessage(), e);
        }
    }
    
    /**
     * 通过名称和类型查找组件
     */
    private <T extends Component> Component findComponentByNameAndType(String name, Class<T> type) {
        return findComponentByNameAndType(SwingUtilities.getWindowAncestor(logTextPane), name, type);
    }
    
    /**
     * 递归地在容器中通过名称和类型查找组件
     */
    private <T extends Component> Component findComponentByNameAndType(Container container, String name, Class<T> type) {
        if (container == null) return null;
        
        for (Component c : container.getComponents()) {
            if (name.equals(c.getName()) && type.isAssignableFrom(c.getClass())) {
                return c;
            }
            
            if (c instanceof Container) {
                Component found = findComponentByNameAndType((Container) c, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 查找字体大小标签组件
     */
    private Component findFontSizeLabel() {
        Container parent = logTextPane.getParent();
        while (parent != null) {
            if (parent instanceof JPanel) {
                for (Component c : ((JPanel) parent).getComponents()) {
                    if (c instanceof JPanel) {
                        for (Component innerC : ((JPanel) c).getComponents()) {
                            if (innerC instanceof JLabel && ((JLabel) innerC).getText().matches("\\d+")) {
                                return innerC;
                            }
                        }
                    }
                }
            }
            parent = parent.getParent();
        }
        return null;
    }
    
    /**
     * 更新文本面板的字体大小
     */
    private void updateFontSize(JTextPane textPane) {
        if (textPane != null) {
            int fontSize = getCurrentFontSize();
            
            // 获取IDE当前编辑器字体
            Font editorFont = UIManager.getFont("Editor.font");
            String fontFamily = editorFont != null ? editorFont.getFamily() : 
                               "JetBrains Mono".equals(UIManager.get("Editor.font.name")) ? 
                               "JetBrains Mono" : Font.MONOSPACED;
            
            Font newFont = new Font(fontFamily, Font.PLAIN, fontSize);
            textPane.setFont(newFont);
            
            // 通知日志管理器字体已更改，可能需要重新格式化文本
            if (project != null) {
                MyBatisLogManager logManager = MyBatisLogManager.getInstance(project);
                logManager.refreshDisplay();
            }
        }
    }

    public JTextPane getTextPane() {
        return logTextPane;
    }
    
    /**
     * 在日志文本中查找最新的完整SQL语句
     * @return 完整SQL语句，如果没有找到则返回null
     */
    private String findLatestCompleteSql() {
        String text = logTextPane.getText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // 查找最后一个"Complete SQL:"行
        Pattern pattern = Pattern.compile("Complete SQL:(.+)(\r?\n|$)");
        Matcher matcher = pattern.matcher(text);
        
        String latestSql = null;
        while (matcher.find()) {
            latestSql = matcher.group(1).trim();
        }
        
        return latestSql;
    }
    
    /**
     * 移除SQL字符串中的参数类型标记
     * 例如: 'admin(String)' -> 'admin'
     */
    private String removeParamTypes(String sql) {
        if (sql == null) return null;
        
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

    /**
     * 重置UI状态并重新初始化所有组件
     * 用于恢复UI在出现问题时的状态
     */
    private void resetUI() {
        LOG.info("Resetting UI state for MyBatis Log Tool Window");
        try {
            // 清空现有的文本
            if (logTextPane != null) {
                StyledDocument doc = logTextPane.getStyledDocument();
                doc.remove(0, doc.getLength());
            }
            
            // 刷新UI组件状态
            refreshUIComponents();
            
            // 重新获取日志管理器
            MyBatisLogManager manager = MyBatisLogManager.getInstance(project);
            
            // 重置管理器状态
            if (manager != null) {
                // 先禁用
                manager.setEnabled(false);
                
                // 清空日志
                manager.clearLogs();
                
                // 再次设置文本面板
                if (logTextPane != null) {
                    manager.setTextPane(logTextPane);
                }
                
                // 再次启用
                manager.setEnabled(true);
                
                LOG.info("UI reset completed successfully");
                showStatusMessage("UI has been reset and reconnected");
            } else {
                LOG.error("Failed to get MyBatisLogManager instance for UI reset");
                showStatusMessage("Failed to reset UI: Manager not available");
            }
        } catch (Exception e) {
            LOG.error("Error resetting UI: " + e.getMessage(), e);
            showStatusMessage("Error resetting UI: " + e.getMessage());
        }
    }
    
    /**
     * 刷新所有UI组件的状态
     * 用于恢复可能无响应的按钮和控件
     */
    private void refreshUIComponents() {
        try {
            LOG.info("Refreshing UI components");
            
            // 获取主面板，如果为null，则返回
            Container parent = logTextPane.getParent();
            if (parent == null) {
                LOG.warn("Cannot find parent container of text pane");
                return;
            }
            
            // 向上查找，直到找到主面板
            Container mainPanel = parent;
            while (mainPanel != null && !(mainPanel.getLayout() instanceof BorderLayout)) {
                mainPanel = mainPanel.getParent();
            }
            
            if (mainPanel == null) {
                LOG.warn("Cannot find main panel");
                return;
            }
            
            // 递归刷新所有组件
            refreshComponents(mainPanel);
            
            // 确保文本窗格可见且活跃
            updateFontSize(logTextPane);
            logTextPane.revalidate();
            logTextPane.repaint();
            
            LOG.info("UI components refreshed successfully");
        } catch (Exception e) {
            LOG.error("Error refreshing UI components: " + e.getMessage(), e);
        }
    }
    
    /**
     * 递归刷新容器内的所有组件
     */
    private void refreshComponents(Container container) {
        // 刷新容器自身
        container.revalidate();
        container.repaint();
        
        // 刷新容器内的所有组件
        for (Component comp : container.getComponents()) {
            // 特殊处理按钮：确保事件监听器正常工作
            if (comp instanceof JButton) {
                JButton button = (JButton) comp;
                button.revalidate();
                button.repaint();
            }
            // 特殊处理文本字段：确保文档监听器正常工作
            else if (comp instanceof JTextField) {
                JTextField textField = (JTextField) comp;
                textField.revalidate();
                textField.repaint();
            }
            // 递归处理子容器
            else if (comp instanceof Container) {
                refreshComponents((Container) comp);
            } else {
                comp.revalidate();
                comp.repaint();
            }
        }
    }
} 