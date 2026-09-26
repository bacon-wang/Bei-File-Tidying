package com.example.tidying;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Small desktop shell around the existing planner and executor. */
public final class FileTidyingGui {
  private final JFrame frame = new JFrame("Bei File Tidying");
  private final JTextField rootField = new JTextField();
  private final JTextField targetField = new JTextField();
  private final JTextField backendField = new JTextField();
  private final JTextArea output = new JTextArea();
  private final JTextArea log = new JTextArea();
  private final JCheckBox developerMode = new JCheckBox("开发者模式");
  private List<FileTidyingAssistant.Plan> plans = List.of();
  private FileTidyingAssistant.Config config;

  private FileTidyingGui() {
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setMinimumSize(new Dimension(920, 620));
    output.setEditable(false);
    log.setEditable(false);
    output.setLineWrap(false);
    log.setLineWrap(true);
    output.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
    log.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
    loadDefaults();
    FileTidyingAssistant.setStatusListener(line -> SwingUtilities.invokeLater(() -> {
      log.append(line + "\n");
      log.setCaretPosition(log.getDocument().getLength());
    }));
    frame.setContentPane(buildContent());
    frame.pack();
    frame.setLocationByPlatform(true);
  }

  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> new FileTidyingGui().frame.setVisible(true));
  }

  private JPanel buildContent() {
    JPanel main = new JPanel(new BorderLayout(8, 8));
    main.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    main.add(buildToolbar(), BorderLayout.NORTH);
    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(output), new JScrollPane(log));
    split.setResizeWeight(0.72);
    main.add(split, BorderLayout.CENTER);
    developerMode.addActionListener(e -> log.setVisible(developerMode.isSelected()));
    log.setVisible(false);
    return main;
  }

  private JPanel buildToolbar() {
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(3, 3, 3, 3);
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 0;
    panel.add(new JLabel("感知根目录"), at(c, 0, 0));
    c.weightx = 1;
    panel.add(rootField, at(c, 1, 0));
    c.weightx = 0;
    panel.add(button("选择", e -> chooseDirectory(rootField)), at(c, 2, 0));
    panel.add(new JLabel("目标目录"), at(c, 0, 1));
    c.weightx = 1;
    panel.add(targetField, at(c, 1, 1));
    c.weightx = 0;
    panel.add(button("选择", e -> chooseDirectory(targetField)), at(c, 2, 1));
    panel.add(new JLabel("后端地址"), at(c, 0, 2));
    c.weightx = 1;
    panel.add(backendField, at(c, 1, 2));
    c.weightx = 0;
    panel.add(developerMode, at(c, 2, 2));
    panel.add(button("生成建议", e -> planAsync()), at(c, 3, 0));
    panel.add(button("执行 APPLY", e -> applyAsync()), at(c, 3, 1));
    panel.add(button("撤销最近一次", e -> undoAsync()), at(c, 3, 2));
    return panel;
  }

  private GridBagConstraints at(GridBagConstraints source, int x, int y) {
    GridBagConstraints c = (GridBagConstraints) source.clone();
    c.gridx = x; c.gridy = y;
    return c;
  }

  private JButton button(String text, java.awt.event.ActionListener listener) {
    JButton button = new JButton(text);
    button.addActionListener(listener);
    return button;
  }

  private void loadDefaults() {
    try {
      Properties p = new Properties();
      Path file = Path.of("application.properties");
      if (Files.isRegularFile(file)) try (var reader = Files.newBufferedReader(file)) { p.load(reader); }
      String home = System.getProperty("user.home");
      rootField.setText(p.getProperty("sense.root", home));
      targetField.setText(p.getProperty("target.dir", home + "/Downloads"));
      backendField.setText(p.getProperty("ai.backend-url", "http://127.0.0.1:8787"));
    } catch (IOException e) { showError(e); }
  }

  private void chooseDirectory(JTextField field) {
    JFileChooser chooser = new JFileChooser(field.getText());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) field.setText(chooser.getSelectedFile().toPath().toString());
  }

  private FileTidyingAssistant.Config readConfig() throws IOException {
    Path root = Path.of(rootField.getText().trim()).toAbsolutePath().normalize();
    Path target = Path.of(targetField.getText().trim()).toAbsolutePath().normalize();
    String backend = backendField.getText().trim().replaceAll("/+$", "");
    if (backend.isBlank()) throw new IOException("请填写后端地址");
    FileTidyingAssistant.Config base = FileTidyingAssistant.Config.load();
    return new FileTidyingAssistant.Config(root, target, base.maxBytes(), backend, "", base.model(),
        base.reasoningEffort(), base.wireApi(), base.treeDepth(), base.batchSize(), base.batchConcurrency(),
        base.deleteEmptyDirectories(), base.maxRequests(), base.maxPromptChars(), true);
  }

  private void planAsync() {
    runAsync("正在生成整理建议...", () -> {
      config = readConfig();
      FileTidyingAssistant.validateConfig(config.senseRoot(), config.target());
      List<Path> directories = FileTidyingAssistant.existingDirectories(config.senseRoot(), config.target());
      List<Path> files = FileTidyingAssistant.targetFiles(config.target());
      plans = FileTidyingAssistant.planAll(files, config, config.senseRoot(), directories);
      return renderPlans(plans, config.senseRoot());
    });
  }

  private void applyAsync() {
    if (plans.isEmpty() || config == null) { showError(new IOException("请先生成整理建议")); return; }
    runAsync("正在执行整理...", () -> { FileTidyingAssistant.execute(plans, config.senseRoot(), config.target(), config.deleteEmptyDirectories()); return "整理完成"; });
  }

  private void undoAsync() {
    runAsync("正在撤销...", () -> { Path root = Path.of(rootField.getText().trim()).toAbsolutePath().normalize(); FileTidyingAssistant.undoLast(root); return "撤销命令已完成"; });
  }

  private String renderPlans(List<FileTidyingAssistant.Plan> values, Path root) {
    StringBuilder text = new StringBuilder("整理计划:\n");
    for (var plan : values) text.append('[').append(plan.status()).append("] ")
        .append(root.relativize(plan.source())).append(" -> ").append(root.relativize(plan.target()))
        .append(" ( ").append(plan.reason()).append(" )\n");
    return text.toString();
  }

  private void runAsync(String message, Task task) {
    output.append(message + "\n");
    new SwingWorker<String, Void>() {
      protected String doInBackground() throws Exception { return task.run(); }
      protected void done() {
        try { String result = get(); output.append(result + "\n"); }
        catch (Exception e) { showError(e); }
        output.setCaretPosition(output.getDocument().getLength());
      }
    }.execute();
  }

  private void showError(Exception error) { JOptionPane.showMessageDialog(frame, error.getMessage(), "操作失败", JOptionPane.ERROR_MESSAGE); }

  @FunctionalInterface private interface Task { String run() throws Exception; }
}
