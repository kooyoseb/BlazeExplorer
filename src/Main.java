import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.concurrent.*;
import java.awt.datatransfer.DataFlavor;



public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("초고속 파일 탐색기");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1000, 600);

            JPanel contentPanel = new JPanel(new BorderLayout());
            JSplitPane splitPane = new JSplitPane();

            FileTreePanel treePanel = new FileTreePanel();
            FileListPanel listPanel = new FileListPanel();
            StatusBar statusBar = new StatusBar();

            treePanel.setFileSelectListener(file -> {
                listPanel.showFiles(file);
                statusBar.setStatus("선택한 폴더: " + file.getAbsolutePath());
            });

            splitPane.setLeftComponent(treePanel);
            splitPane.setRightComponent(listPanel);
            splitPane.setDividerLocation(300);

            contentPanel.add(splitPane, BorderLayout.CENTER);
            contentPanel.add(statusBar, BorderLayout.SOUTH);
            contentPanel.add(listPanel.getSearchBar(), BorderLayout.NORTH);

            frame.getContentPane().add(contentPanel);
            frame.setVisible(true);
        });
    }
}

class FileTreePanel extends JPanel {
    private JTree tree;
    private DefaultTreeModel model;
    private FileSelectListener listener;

    public FileTreePanel() {
        setLayout(new BorderLayout());
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("컴퓨터");
        model = new DefaultTreeModel(root);
        tree = new JTree(model);

        File[] roots = File.listRoots();
        for (File fileRoot : roots) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(fileRoot);
            root.add(node);
            loadChildren(node, fileRoot);
        }

        tree.addTreeSelectionListener(e -> {
            TreePath path = tree.getSelectionPath();
            if (path == null) return;
            Object last = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (last instanceof File && listener != null) {
                listener.fileSelected((File) last);
            }
        });

        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    private void loadChildren(DefaultMutableTreeNode node, File file) {
        File[] dirs = file.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            node.add(new DefaultMutableTreeNode(dir));
        }
    }

    public void setFileSelectListener(FileSelectListener listener) {
        this.listener = listener;
    }
}

interface FileSelectListener {
    void fileSelected(File file);
}

class FileListPanel extends JPanel {
    private JList<File> fileList;
    private DefaultListModel<File> model;
    private ExecutorService executor = Executors.newFixedThreadPool(8);
    private JTextField searchField;
    private JPanel searchBar;

    public FileListPanel() {
        setLayout(new BorderLayout());
        model = new DefaultListModel<>();
        fileList = new JList<>(model);
        fileList.setCellRenderer(new FileCellRenderer());

        fileList.setComponentPopupMenu(createPopupMenu());
        fileList.setDragEnabled(true);
        fileList.setDropMode(DropMode.ON);
        fileList.setTransferHandler(new FileTransferHandler());

        add(new JScrollPane(fileList), BorderLayout.CENTER);
        createSearchBar();
    }

    public void showFiles(File dir) {
        model.clear();
        executor.submit(() -> {
            File[] files = dir.listFiles();
            if (files == null) return;
            SwingUtilities.invokeLater(() -> {
                for (File file : files) {
                    model.addElement(file);
                }
            });
        });
    }

    private void createSearchBar() {
        searchField = new JTextField();
        searchField.addActionListener(e -> filterFiles());
        searchBar = new JPanel(new BorderLayout());
        searchBar.add(new JLabel(" 검색: "), BorderLayout.WEST);
        searchBar.add(searchField, BorderLayout.CENTER);
    }

    public JPanel getSearchBar() {
        return searchBar;
    }

    private void filterFiles() {
        String keyword = searchField.getText().toLowerCase();
        for (int i = 0; i < model.size(); i++) {
            File f = model.get(i);
            if (!f.getName().toLowerCase().contains(keyword)) {
                model.remove(i);
                i--;
            }
        }
    }

    private JPopupMenu createPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("복사");
        JMenuItem moveItem = new JMenuItem("이동");
        JMenuItem deleteItem = new JMenuItem("삭제");

        copyItem.addActionListener(e -> performAction("copy"));
        moveItem.addActionListener(e -> performAction("move"));
        deleteItem.addActionListener(e -> performAction("delete"));

        menu.add(copyItem);
        menu.add(moveItem);
        menu.add(deleteItem);
        return menu;
    }

    private void performAction(String action) {
        File selected = fileList.getSelectedValue();
        if (selected == null) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int option = chooser.showDialog(this, "대상 선택");
        if (option == JFileChooser.APPROVE_OPTION) {
            File targetDir = chooser.getSelectedFile();
            File target = new File(targetDir, selected.getName());
            try {
                switch (action) {
                    case "copy" -> FileUtils.fastCopy(selected, target);
                    case "move" -> FileUtils.fastMove(selected, target);
                    case "delete" -> Files.delete(selected.toPath());
                }
                showFiles(targetDir);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "오류: " + ex.getMessage());
            }
        }
    }

    static class FileCellRenderer extends DefaultListCellRenderer {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof File file) {
                String info = file.getName();
                if (file.isFile()) {
                    info += String.format(" [%,d bytes, %s]", file.length(), sdf.format(file.lastModified()));
                }
                setText(info);
            }
            return this;
        }
    }
}

class StatusBar extends JPanel {
    private JLabel statusLabel;

    public StatusBar() {
        setLayout(new BorderLayout());
        statusLabel = new JLabel(" 준비됨");
        add(statusLabel, BorderLayout.WEST);
    }

    public void setStatus(String message) {
        statusLabel.setText(" 🔹 " + message);
    }
}

class FileUtils {
    public static void fastCopy(File source, File target) throws IOException {
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public static void fastMove(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}

class FileTransferHandler extends TransferHandler {
    public boolean canImport(TransferHandler.TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    public boolean importData(TransferHandler.TransferSupport support) {
        try {
            java.util.List<File> files = (java.util.List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
            JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
            Component comp = support.getComponent();
            if (comp instanceof JList<?> list && dl != null) {
                File targetDir = new File(System.getProperty("user.home"));
                for (File f : files) {
                    FileUtils.fastCopy(f, new File(targetDir, f.getName()));
                }
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
