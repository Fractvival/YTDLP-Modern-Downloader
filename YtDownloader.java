import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class YtDownloader extends JFrame {

    // ================== KONSTANTY ==================
    private static final Path APP_DIR = Paths.get("").toAbsolutePath();
    private static final Path DOWNLOADS_DIR = APP_DIR.resolve("Downloads");
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String YTDLP_NAME = IS_WINDOWS ? "yt-dlp.exe" : "yt-dlp";
    private static final String FFMPEG_NAME = IS_WINDOWS ? "ffmpeg.exe" : "ffmpeg";
    private static final Path YTDLP_PATH = APP_DIR.resolve(YTDLP_NAME);
    private static final Path FFMPEG_PATH = APP_DIR.resolve(FFMPEG_NAME);

    // ================== UI KOMPONENTY ==================
    private JTextField urlField;
    private JComboBox<String> formatCombo;
    private JComboBox<String> qualityCombo;
    private JButton downloadBtn;
    private JButton folderBtn;
    private JProgressBar progressBar;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JCheckBox cookiesCheck;
    private JComboBox<String> browserCombo;
    private JButton cookiesFileBtn;
    private Path cookiesFile = null;
    private Path downloadDir = DOWNLOADS_DIR;
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    public YtDownloader() {
        setTitle("yt-dlp Modern Downloader (Java)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(820, 680);
        setMinimumSize(new Dimension(720, 580));
        setLocationRelativeTo(null);

        // Tmavý vzhled
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            UIManager.put("control", new Color(45, 45, 48));
            UIManager.put("info", new Color(45, 45, 48));
            UIManager.put("nimbusBase", new Color(30, 30, 35));
            UIManager.put("nimbusFocus", new Color(100, 150, 255));
            UIManager.put("text", Color.WHITE);
            UIManager.put("nimbusLightBackground", new Color(50, 50, 55));
        } catch (Exception ignored) {}

        initUI();
        // Spusť kontrolu nástrojů na pozadí
        new Thread(this::checkAndUpdateTools).start();
    }

    private void initUI() {
        JPanel main = new JPanel(new BorderLayout(12, 12));
        main.setBorder(new EmptyBorder(15, 15, 15, 15));
        main.setBackground(new Color(40, 40, 45));

        // ===== STATUS =====
        statusLabel = new JLabel("  Kontroluji nástroje…");
        statusLabel.setForeground(new Color(180, 180, 180));
        statusLabel.setFont(statusLabel.getFont().deriveFont(13f));
        main.add(statusLabel, BorderLayout.NORTH);

        // ===== STŘED =====
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        // URL + Paste button
        center.add(createLabel("YouTube / playlist URL:"));
        JPanel urlPanel = new JPanel(new BorderLayout(8, 0));
        urlPanel.setOpaque(false);
        urlPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        urlField = new JTextField();
        urlField.setFont(urlField.getFont().deriveFont(14f));
        // Explicitní povolení kontextového menu (pravý klik → Vložit)
        urlField.setComponentPopupMenu(createTextPopup(urlField));

        JButton pasteBtn = new JButton("Vložit");
        pasteBtn.setPreferredSize(new Dimension(90, 36));
        pasteBtn.setToolTipText("Vložit obsah ze schránky (Ctrl+V)");
        pasteBtn.addActionListener(e -> pasteFromClipboard());

        urlPanel.add(urlField, BorderLayout.CENTER);
        urlPanel.add(pasteBtn, BorderLayout.EAST);
        center.add(urlPanel);
        center.add(Box.createVerticalStrut(12));

        // Formát + Kvalita + Složka
        JPanel opts = new JPanel(new GridLayout(1, 3, 12, 0));
        opts.setOpaque(false);
        opts.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        // Formát
        JPanel fPanel = new JPanel(new BorderLayout(0, 4));
        fPanel.setOpaque(false);
        fPanel.add(createLabel("Formát:"), BorderLayout.NORTH);
        formatCombo = new JComboBox<>(new String[]{"mp3", "flac", "video"});
        formatCombo.addActionListener(e -> updateQualityOptions());
        fPanel.add(formatCombo, BorderLayout.CENTER);
        opts.add(fPanel);

        // Kvalita
        JPanel qPanel = new JPanel(new BorderLayout(0, 4));
        qPanel.setOpaque(false);
        qPanel.add(createLabel("Kvalita:"), BorderLayout.NORTH);
        qualityCombo = new JComboBox<>(new String[]{"best", "320k", "256k", "192k", "128k"});
        qPanel.add(qualityCombo, BorderLayout.CENTER);
        opts.add(qPanel);

        // Složka
        JPanel dPanel = new JPanel(new BorderLayout(0, 4));
        dPanel.setOpaque(false);
        dPanel.add(createLabel("Složka:"), BorderLayout.NORTH);
        folderBtn = new JButton("Downloads");
        folderBtn.addActionListener(e -> chooseFolder());
        dPanel.add(folderBtn, BorderLayout.CENTER);
        opts.add(dPanel);

        center.add(opts);
        center.add(Box.createVerticalStrut(14));

        // Cookies
        JPanel cookiePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        cookiePanel.setOpaque(false);
        cookiesCheck = new JCheckBox("Použít cookies");
        cookiesCheck.setForeground(Color.WHITE);
        browserCombo = new JComboBox<>(new String[]{"chrome", "firefox", "edge", "opera", "brave", "chromium"});
        browserCombo.setEnabled(false);
        cookiesFileBtn = new JButton("Soubor cookies.txt…");
        cookiesFileBtn.setEnabled(false);

        cookiesCheck.addActionListener(e -> {
            boolean on = cookiesCheck.isSelected();
            browserCombo.setEnabled(on);
            cookiesFileBtn.setEnabled(on);
        });
        cookiesFileBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Vyber cookies.txt");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                cookiesFile = fc.getSelectedFile().toPath();
                log("Cookies soubor: " + cookiesFile.getFileName());
            }
        });

        cookiePanel.add(cookiesCheck);
        cookiePanel.add(new JLabel("Prohlížeč:"));
        cookiePanel.add(browserCombo);
        cookiePanel.add(cookiesFileBtn);
        center.add(cookiePanel);
        center.add(Box.createVerticalStrut(16));

        // Tlačítko
        downloadBtn = new JButton("⬇  Stáhnout");
        downloadBtn.setFont(downloadBtn.getFont().deriveFont(Font.BOLD, 16f));
        downloadBtn.setPreferredSize(new Dimension(0, 48));
        downloadBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        downloadBtn.setEnabled(false);
        downloadBtn.addActionListener(e -> startDownload());
        center.add(downloadBtn);
        center.add(Box.createVerticalStrut(12));

        // Progress
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Připraveno");
        progressBar.setPreferredSize(new Dimension(0, 28));
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        center.add(progressBar);

        main.add(center, BorderLayout.CENTER);

        // ===== LOG =====
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(30, 30, 35));
        logArea.setForeground(new Color(200, 200, 200));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new Dimension(0, 220));
        main.add(scroll, BorderLayout.SOUTH);

        setContentPane(main);
    }

    private JLabel createLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(220, 220, 220));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        return l;
    }

    /** Kontextové menu pro textové pole (pravý klik) */
    private JPopupMenu createTextPopup(JTextField field) {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem cut = new JMenuItem("Vyjmout");
        cut.addActionListener(e -> field.cut());
        popup.add(cut);

        JMenuItem copy = new JMenuItem("Kopírovat");
        copy.addActionListener(e -> field.copy());
        popup.add(copy);

        JMenuItem paste = new JMenuItem("Vložit");
        paste.addActionListener(e -> field.paste());
        popup.add(paste);

        JMenuItem selectAll = new JMenuItem("Vybrat vše");
        selectAll.addActionListener(e -> field.selectAll());
        popup.add(selectAll);

        return popup;
    }

    /** Vloží obsah ze systémové schránky do URL pole */
    private void pasteFromClipboard() {
        try {
            java.awt.datatransfer.Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            java.awt.datatransfer.Transferable contents = clipboard.getContents(null);
            if (contents != null && contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                String text = (String) contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (text != null && !text.isBlank()) {
                    urlField.setText(text.trim());
                    urlField.requestFocusInWindow();
                }
            }
        } catch (Exception ex) {
            log("Nepodařilo se vložit ze schránky: " + ex.getMessage());
        }
    }

    private void updateQualityOptions() {
        String fmt = (String) formatCombo.getSelectedItem();
        qualityCombo.removeAllItems();
        if ("video".equals(fmt)) {
            for (String q : new String[]{"best", "1080p", "720p", "480p", "360p"}) qualityCombo.addItem(q);
        } else {
            for (String q : new String[]{"best", "320k", "256k", "192k", "128k"}) qualityCombo.addItem(q);
        }
        qualityCombo.setSelectedIndex(0);
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser(downloadDir.toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            downloadDir = fc.getSelectedFile().toPath();
            folderBtn.setText(downloadDir.getFileName().toString());
        }
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + java.time.LocalTime.now().withNano(0) + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void setProgress(int value, String text) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(value);
            progressBar.setString(text);
        });
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText("  " + text));
    }

    // ================== KONTROLA A STAHOVÁNÍ NÁSTROJŮ ==================
    private void checkAndUpdateTools() {
        log("=== Kontrola nástrojů ===");
        boolean okY = ensureYtDlp();
        boolean okF = ensureFfmpeg();
        if (okY && okF) {
            setStatus("✓ Všechny nástroje jsou připraveny");
            SwingUtilities.invokeLater(() -> downloadBtn.setEnabled(true));
            log("Připraveno ke stahování!");
        } else {
            setStatus("⚠ Některé nástroje chybí – zkus restart");
        }
    }

    private boolean ensureYtDlp() {
        try {
            String localVer = getVersion(YTDLP_PATH, "--version");
            log("Lokální yt-dlp: " + (localVer != null ? localVer : "chybí"));

            // Zjisti nejnovější verzi přes GitHub API
            String json = httpGet("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest");
            String latest = extractJson(json, "\"tag_name\"");
            if (latest != null) latest = latest.replace("\"", "").replace("v", "");

            String assetName = IS_WINDOWS ? "yt-dlp.exe" : "yt-dlp";
            String downloadUrl = null;
            // Hledáme asset
            Pattern p = Pattern.compile("\"name\"\\s*:\\s*\"" + Pattern.quote(assetName) + "\".*?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(json);
            if (m.find()) downloadUrl = m.group(1);

            if (downloadUrl == null) {
                log("Nepodařilo se najít download URL pro yt-dlp");
                return localVer != null;
            }

            if (localVer != null && localVer.equals(latest)) {
                log("yt-dlp je aktuální (" + localVer + ")");
                return true;
            }

            log("Stahuji yt-dlp " + latest + "…");
            setProgress(0, "Stahuji yt-dlp…");
            downloadFile(downloadUrl, YTDLP_PATH, pct -> setProgress(pct, "yt-dlp " + pct + "%"));
            if (!IS_WINDOWS) YTDLP_PATH.toFile().setExecutable(true);
            log("yt-dlp úspěšně nainstalován: " + latest);
            return true;
        } catch (Exception e) {
            log("Chyba yt-dlp: " + e.getMessage());
            return YTDLP_PATH.toFile().exists();
        }
    }

    private boolean ensureFfmpeg() {
        try {
            if (FFMPEG_PATH.toFile().exists()) {
                String ver = getVersion(FFMPEG_PATH, "-version");
                log("ffmpeg je přítomen" + (ver != null ? " (" + ver.split("\n")[0] + ")" : ""));
                return true;
            }

            log("ffmpeg chybí → stahuji…");
            setProgress(0, "Stahuji ffmpeg…");

            String url;
            Path archive;
            if (IS_WINDOWS) {
                url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
                archive = APP_DIR.resolve("ffmpeg.zip");
            } else {
                url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz";
                archive = APP_DIR.resolve("ffmpeg.tar.xz");
            }

            downloadFile(url, archive, pct -> setProgress(pct, "ffmpeg " + pct + "%"));

            // Rozbalení
            Path tmp = APP_DIR.resolve("ffmpeg_tmp");
            if (Files.exists(tmp)) deleteRecursive(tmp);
            Files.createDirectories(tmp);

            if (IS_WINDOWS) {
                extractZip(archive, tmp);
            } else {
                // Linux – použijeme systémový tar
                ProcessBuilder pb = new ProcessBuilder("tar", "-xf", archive.toString(), "-C", tmp.toString());
                pb.inheritIO();
                int code = pb.start().waitFor();
                if (code != 0) throw new IOException("tar selhal");
            }

            // Najdi ffmpeg binary
            Path found = findFile(tmp, FFMPEG_NAME);
            if (found == null) found = findFile(tmp, "ffmpeg");
            if (found == null) throw new IOException("ffmpeg binary nenalezen v archivu");

            Files.copy(found, FFMPEG_PATH, StandardCopyOption.REPLACE_EXISTING);
            if (!IS_WINDOWS) FFMPEG_PATH.toFile().setExecutable(true);

            // Úklid
            deleteRecursive(tmp);
            Files.deleteIfExists(archive);

            log("ffmpeg úspěšně nainstalován");
            setProgress(100, "ffmpeg hotovo");
            return true;
        } catch (Exception e) {
            log("Chyba ffmpeg: " + e.getMessage());
            return FFMPEG_PATH.toFile().exists();
        }
    }

    // ================== STAHOVÁNÍ MÉDIÍ ==================
    private void startDownload() {
        if (downloading.get()) return;
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vlož prosím URL.", "Chybí URL", JOptionPane.WARNING_MESSAGE);
            return;
        }

        downloading.set(true);
        downloadBtn.setEnabled(false);
        setProgress(0, "Spouštím…");

        new Thread(() -> {
            try {
                log("Začínám stahovat: " + url);
                String fmt = (String) formatCombo.getSelectedItem();
                String quality = (String) qualityCombo.getSelectedItem();

                // Výstupní šablona – alba do složky, singles do NA
                String outTmpl = downloadDir.resolve("%(album,playlist_title,NA)s").resolve("%(title)s.%(ext)s").toString();

                java.util.List<String> cmd = new java.util.ArrayList<>();
                cmd.add(YTDLP_PATH.toString());
                cmd.add("--no-mtime");
                cmd.add("--embed-metadata");
                cmd.add("--embed-thumbnail");
                cmd.add("--newline");
                cmd.add("--progress");

                // Cookies
                if (cookiesCheck.isSelected()) {
                    if (cookiesFile != null && Files.exists(cookiesFile)) {
                        cmd.add("--cookies");
                        cmd.add(cookiesFile.toString());
                    } else {
                        cmd.add("--cookies-from-browser");
                        cmd.add((String) browserCombo.getSelectedItem());
                    }
                }

                if ("mp3".equals(fmt) || "flac".equals(fmt)) {
                    cmd.add("-x");
                    cmd.add("--audio-format");
                    cmd.add(fmt);
                    cmd.add("--audio-quality");
                    cmd.add("best".equals(quality) ? "0" : quality);
                } else {
                    if ("best".equals(quality)) {
                        cmd.add("-f");
                        cmd.add("bv*+ba/b");
                    } else {
                        String h = quality.replace("p", "");
                        cmd.add("-f");
                        cmd.add("bv*[height<=" + h + "]+ba/b");
                    }
                    cmd.add("--merge-output-format");
                    cmd.add("mp4");
                }

                cmd.add("--ffmpeg-location");
                cmd.add(APP_DIR.toString());
                cmd.add("-o");
                cmd.add(outTmpl);
                cmd.add(url);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                Pattern pctPattern = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log(line);
                        Matcher m = pctPattern.matcher(line);
                        if (m.find()) {
                            try {
                                int pct = (int) Double.parseDouble(m.group(1));
                                setProgress(pct, pct + " %");
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }

                int code = proc.waitFor();
                if (code == 0) {
                    setProgress(100, "Hotovo!");
                    log("✓ Stažení úspěšně dokončeno");
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, "Stažení úspěšně dokončeno!", "Hotovo", JOptionPane.INFORMATION_MESSAGE));
                } else {
                    log("⚠ Proces skončil s kódem " + code);
                    setProgress(0, "Chyba");
                }
            } catch (Exception e) {
                log("Chyba: " + e.getMessage());
                setProgress(0, "Chyba");
            } finally {
                downloading.set(false);
                SwingUtilities.invokeLater(() -> downloadBtn.setEnabled(true));
            }
        }).start();
    }

    // ================== POMOCNÉ METODY ==================
    private String getVersion(Path exe, String arg) {
        if (!Files.exists(exe)) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe.toString(), arg);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return r.readLine();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "YtDownloader-Java");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes());
        }
    }

    private String extractJson(String json, String key) {
        Pattern p = Pattern.compile(key + "\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private void downloadFile(String urlStr, Path dest, java.util.function.IntConsumer progress) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "YtDownloader-Java");
        long total = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[8192];
            long downloaded = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0 && progress != null) {
                    progress.accept((int) (downloaded * 100 / total));
                }
            }
        }
    }

    private void extractZip(Path zip, Path dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) continue;
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path findFile(Path dir, String name) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> p.getFileName().toString().equals(name))
                    .findFirst().orElse(null);
        }
    }

    private void deleteRecursive(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
        }
    }

    public static void main(String[] args) {
        try {
            Files.createDirectories(DOWNLOADS_DIR);
        } catch (IOException ignored) {}
        SwingUtilities.invokeLater(() -> new YtDownloader().setVisible(true));
    }
}
