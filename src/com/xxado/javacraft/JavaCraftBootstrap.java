package com.xxado.javacraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.IntBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.zip.ZipFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Personal launcher for the owner's official Minecraft Java client. */
public final class JavaCraftBootstrap {
    // Public Microsoft application identifier; it is intentionally shipped with JavaCraft.
    private static final String DEFAULT_MICROSOFT_CLIENT_ID =
            "8c13cde3-9c87-49bd-b547-cd73e752261b";
    private static final String MOD_UPDATE_MANIFEST =
            "https://appt-engine.online/javacraft/mod-updates/manifest-v1.json";
    private static final String MOD_UPDATE_HOST = "appt-engine.online";
    private static final String MOD_UPDATE_FILE_PREFIX = "/javacraft/mod-updates/files/";
    private static final java.util.Set<String> MANAGED_MOD_IDS = java.util.Set.of(
            "black-shield-community", "controlify", "fabric-api", "ferritecore",
            "immediatelyfast", "javacraft-account", "lithium", "yacl");
    private static final int KEY_COLUMNS = 10;
    private static final String[] KEYS = {
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
            "a", "s", "d", "f", "g", "h", "j", "k", "l", "@",
            "z", "x", "c", "v", "b", "n", "m", "_", "-", ".",
            "/", ":", ";", "'", "\"", "!", "?", "+", "#", "&",
            "(", ")", "[", "]", "{", "}", "\\", "CLEAR", "DEL", "ENTER"
    };
    private static final Color BACKGROUND = new Color(29, 33, 40);
    private static final Color PANEL = new Color(39, 44, 53);
    private static final Color ACCENT = new Color(151, 105, 225);
    private static volatile JFrame installerFrame;
    private static volatile JLabel installerStatus;

    private JavaCraftBootstrap() { }

    public static void main(String[] arguments) {
        Path home = Path.of(System.getProperty("javacraft.home", "C:\\JavaCraftPS5"))
                .toAbsolutePath().normalize();
        try {
            LaunchSelection selection = configuredLaunch(home);
            JsonObject metadata = JsonParser.parseString(Files.readString(selection.launchFile,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            launchGame(home, metadata, selection.account, selection.version,
                    selection.inputMode, selection.instanceId);
        } catch (Exception error) {
            closeInstaller();
            try { Files.deleteIfExists(home.resolve(".javacraft-game-ready")); }
            catch (IOException ignoredError) { }
            showError("JavaCraft could not start Minecraft.\n\n" + safeMessage(error));
        }
    }

    /** Reads the native PS5 menu's JSON and starts Minecraft in this JVM. */
    private static LaunchSelection configuredLaunch(Path home) throws Exception {
        JsonObject settings = readLauncherSettings(home);
        String version = settings.has("version") ? settings.get("version").getAsString() : "26.3";
        LaunchProfile profile = launchProfiles(home).stream()
                .filter(item -> item.version.equals(version)).findFirst()
                .orElseThrow(() -> new IOException("Minecraft " + version + " is not installed."));
        ensureVersionInstalled(home, version, profile.file);
        String inputMode = settings.has("inputMode") ? settings.get("inputMode").getAsString() : "controller";
        if (!inputMode.equals("controller") && !inputMode.equals("keyboardMouse"))
            throw new IOException("The selected control mode is invalid.");
        String instanceId = settings.has("instanceId")
                ? settings.get("instanceId").getAsString() : "default";
        Path gameDir = resolveGameDirectory(home, version, instanceId);
        installModpack(home, version, gameDir);
        updateManagedMods(home, version, gameDir);
        String playerName = settings.has("playerName") ? settings.get("playerName").getAsString() : "Player";
        if (!validName(playerName)) throw new IOException("Choose a player name with 3–16 letters, numbers, or underscores.");
        AuthSession account;
        String authMode = settings.has("authMode") ? settings.get("authMode").getAsString() : "offline";
        if (authMode.equals("microsoft")) {
            String code = settings.has("pairingCode") ? settings.get("pairingCode").getAsString() : "";
            if (!code.matches("(?i)[0-9a-f]{32}"))
                throw new IOException("The online pairing key is missing. Return to the JavaCraft settings screen and launch again.");
            AtomicReference<AuthSession> received = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            try (CompanionPairing pairing = CompanionPairing.start("", code, session -> {
                received.set(session);
                completed.countDown();
            })) {
                JFrame waitWindow = showPairingWait(code, pairing.port);
                boolean signedIn;
                try { signedIn = completed.await(5, TimeUnit.MINUTES); }
                finally { closePairingWait(waitWindow); }
                if (!signedIn || received.get() == null)
                    throw new IOException("Online sign-in timed out. Launch again and retry with the PC helper.");
                account = received.get();
            }
        } else if (authMode.equals("offline")) {
            account = offlineSession(playerName);
        } else {
            throw new IOException("The selected account mode is invalid.");
        }
        return new LaunchSelection(version, profile.file, account, inputMode, instanceId);
    }

    private static JFrame showPairingWait(String code, int port) throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) return null;
        AtomicReference<JFrame> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("JavaCraft Microsoft sign-in");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setSize(820, 300);
            frame.setResizable(false);
            frame.setLocationRelativeTo(null);
            JPanel panel = new JPanel(new GridLayout(3, 1, 8, 14));
            panel.setBackground(BACKGROUND);
            panel.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
            JLabel title = new JLabel("Sign in with the JavaCraft PC helper", SwingConstants.CENTER);
            title.setForeground(Color.WHITE);
            title.setFont(new Font("SansSerif", Font.BOLD, 24));
            JLabel key = new JLabel("One-time key: " + code, SwingConstants.CENTER);
            key.setForeground(new Color(190, 165, 255));
            key.setFont(new Font("Monospaced", Font.BOLD, 22));
            JLabel instruction = new JLabel("Enter the PS5 IP, port " + port
                    + ", and this key in the helper. Microsoft sign-in opens on your PC.", SwingConstants.CENTER);
            instruction.setForeground(new Color(220, 224, 231));
            instruction.setFont(new Font("SansSerif", Font.PLAIN, 16));
            panel.add(title);
            panel.add(key);
            panel.add(instruction);
            frame.setContentPane(panel);
            frame.setVisible(true);
            result.set(frame);
        });
        return result.get();
    }

    private static void closePairingWait(JFrame frame) {
        if (frame == null) return;
        SwingUtilities.invokeLater(frame::dispose);
    }

    private static AuthSession offlineSession(String name) {
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return new AuthSession(name, uuid, "0", "0", "legacy", "0");
    }

    private static LaunchSelection showLauncher(Path home) throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless())
            throw new IOException("The JavaCraft launcher screen is unavailable.");
        JsonObject savedSettings = readLauncherSettings(home);
        String saved = savedSettings.has("playerName")
                ? savedSettings.get("playerName").getAsString() : "Player";
        Path savedName = home.resolve("player-name.txt");
        if (!savedSettings.has("playerName") && Files.isRegularFile(savedName)) {
            String candidate = Files.readString(savedName, StandardCharsets.UTF_8).trim();
            if (validName(candidate)) saved = candidate;
        }
        if (!validName(saved)) saved = "Player";
        final String initialName = saved;
        String savedVersion = savedSettings.has("version")
                ? savedSettings.get("version").getAsString() : "26.3";
        String savedInputMode = savedSettings.has("inputMode")
                ? savedSettings.get("inputMode").getAsString() : "controller";
        String savedAuthMode = savedSettings.has("authMode")
                ? savedSettings.get("authMode").getAsString() : "offline";
        List<LaunchProfile> profiles = launchProfiles(home);
        if (profiles.isEmpty()) throw new IOException("No supported Minecraft version is installed.");

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<LaunchSelection> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("JavaCraft");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setSize(1020, 840);
            frame.setResizable(false);
            frame.setLocationRelativeTo(null);
            frame.getContentPane().setBackground(BACKGROUND);

            CardLayout cards = new CardLayout();
            JPanel pages = new JPanel(cards);
            pages.setBackground(BACKGROUND);
            JTextField name = new JTextField(initialName, 16);
            configureTextField(name, 25);
            restrictPlayerName(name);

            BufferedImage titleBackground = readLauncherBackground(home);
            JPanel homePage = new MinecraftTitlePanel(titleBackground);
            homePage.setLayout(new BorderLayout(16, 18));
            homePage.setBackground(BACKGROUND);
            homePage.setBorder(BorderFactory.createEmptyBorder(28, 42, 28, 42));
            JLabel title = new JLabel("MINECRAFT", SwingConstants.CENTER);
            title.setForeground(Color.WHITE);
            title.setFont(new Font("Monospaced", Font.BOLD, 42));
            JLabel subtitle = new JLabel("JavaCraft by V0BIT  •  Choose a version and how you want to play", SwingConstants.CENTER);
            subtitle.setForeground(new Color(190, 196, 207));
            subtitle.setFont(new Font("SansSerif", Font.PLAIN, 18));
            JPanel heading = new JPanel(new GridLayout(2, 1, 4, 4));
            heading.setOpaque(false);
            heading.add(title);
            heading.add(subtitle);
            homePage.add(heading, BorderLayout.NORTH);

            JPanel options = new JPanel();
            options.setOpaque(false);
            options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
            JComboBox<LaunchProfile> version = new JComboBox<>(profiles.toArray(LaunchProfile[]::new));
            version.setFont(new Font("SansSerif", Font.BOLD, 21));
            version.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            profiles.stream().filter(profile -> profile.version.equals(savedVersion)).findFirst()
                    .ifPresent(version::setSelectedItem);
            options.add(label("Minecraft version"));
            options.add(version);
            options.add(Box.createVerticalStrut(18));

            options.add(label("Controls for this session"));
            JComboBox<String> controlMode = new JComboBox<>(new String[]{
                    "Controller only", "Keyboard + mouse only"});
            controlMode.setFont(new Font("SansSerif", Font.BOLD, 18));
            controlMode.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            controlMode.setSelectedIndex("keyboardMouse".equals(savedInputMode) ? 1 : 0);
            options.add(controlMode);
            options.add(Box.createVerticalStrut(12));

            JLabel nameLabel = label("Offline player name");
            nameLabel.setText("Offline player name: " + initialName);
            JButton editName = button("Edit player name");
            editName.addActionListener(event -> cards.show(pages, "keyboard"));
            JPanel nameRow = new JPanel(new BorderLayout(12, 0));
            nameRow.setOpaque(false);
            nameRow.add(nameLabel, BorderLayout.CENTER);
            nameRow.add(editName, BorderLayout.EAST);
            options.add(nameRow);
            options.add(Box.createVerticalStrut(16));

            boolean preferMicrosoft = "microsoft".equals(savedAuthMode);
            JRadioButton offline = new JRadioButton("Play Offline", !preferMicrosoft);
            JRadioButton microsoft = new JRadioButton("Microsoft account");
            offline.setOpaque(false);
            microsoft.setOpaque(false);
            offline.setForeground(Color.WHITE);
            microsoft.setForeground(Color.WHITE);
            offline.setFont(new Font("SansSerif", Font.BOLD, 20));
            microsoft.setFont(new Font("SansSerif", Font.BOLD, 20));
            ButtonGroup accountMode = new ButtonGroup();
            accountMode.add(offline);
            accountMode.add(microsoft);
            microsoft.setSelected(preferMicrosoft);
            options.add(offline);
            options.add(microsoft);

            JLabel signInStatus = label("Microsoft sign-in is configured. Your password and verification stay on Microsoft's page.");
            signInStatus.setFont(new Font("SansSerif", Font.PLAIN, 14));
            options.add(signInStatus);
            options.add(Box.createVerticalStrut(8));

            AtomicReference<AuthSession> signedIn = new AtomicReference<>();
            AtomicReference<SwingWorker<AuthSession, Void>> loginWorker = new AtomicReference<>();
            AtomicReference<CompanionPairing> companionPairing = new AtomicReference<>();
            JButton signIn = button("Sign in to Microsoft");
            JButton cancelSignIn = button("Cancel sign-in");
            cancelSignIn.setEnabled(false);
            signIn.addActionListener(event -> {
                String appId = savedMicrosoftClientId();
                signIn.setEnabled(false);
                cancelSignIn.setEnabled(true);
                signInStatus.setText("Waiting for Microsoft sign-in…");
                SwingWorker<AuthSession, Void> worker = new SwingWorker<>() {
                    @Override protected AuthSession doInBackground() throws Exception {
                        return microsoftSignIn(appId);
                    }
                    @Override protected void done() {
                        signIn.setEnabled(true);
                        cancelSignIn.setEnabled(false);
                        loginWorker.set(null);
                        if (isCancelled()) {
                            signInStatus.setText("Sign-in cancelled.");
                            return;
                        }
                        try {
                            AuthSession account = get();
                            signedIn.set(account);
                            microsoft.setText("Microsoft account: " + account.name);
                            microsoft.setSelected(true);
                            signInStatus.setText("Signed in for this launch. Your token is kept in memory only.");
                        } catch (Exception error) {
                            Throwable cause = error.getCause() == null ? error : error.getCause();
                            signInStatus.setText("Microsoft sign-in failed. Check the app ID or connection.");
                            showError("Microsoft sign-in failed.\n\n" + safeMessage(cause));
                        }
                    }
                };
                loginWorker.set(worker);
                worker.execute();
            });
            cancelSignIn.addActionListener(event -> {
                SwingWorker<AuthSession, Void> worker = loginWorker.getAndSet(null);
                if (worker != null) worker.cancel(true);
                signedIn.set(null);
                signIn.setEnabled(true);
                cancelSignIn.setEnabled(false);
                microsoft.setText("Microsoft account");
                signInStatus.setText("Sign-in cancelled.");
            });
            JPanel signInButtons = new JPanel(new GridLayout(1, 2, 10, 0));
            signInButtons.setOpaque(false);
            signInButtons.add(signIn);
            signInButtons.add(cancelSignIn);
            options.add(signInButtons);
            JButton pcSignIn = button("Sign in using the PC helper");
            pcSignIn.addActionListener(event -> {
                String appId = savedMicrosoftClientId();
                try {
                    CompanionPairing old = companionPairing.getAndSet(null);
                    if (old != null) old.close();
                    CompanionPairing next = CompanionPairing.start(appId, account ->
                            SwingUtilities.invokeLater(() -> {
                                signedIn.set(account);
                                microsoft.setText("Microsoft account: " + account.name);
                                microsoft.setSelected(true);
                                signInStatus.setText("PC sign-in received. Session is held in memory only.");
                                CompanionPairing active = companionPairing.getAndSet(null);
                                if (active != null) active.close();
                            }));
                    companionPairing.set(next);
                    signInStatus.setText("Waiting for the JavaCraft PC helper…");
                    String addresses = localIpv4Addresses();
                    JOptionPane.showMessageDialog(frame,
                            "On the PC helper, enter one of these PS5 addresses:\n" + addresses
                                    + "\nPort: " + next.port
                                    + "\nOne-time pairing key:\n" + next.pairingCode
                                    + "\n\nThe helper signs in on Microsoft's page, then sends an encrypted one-time session over your LAN.",
                            "JavaCraft PC sign-in", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception error) {
                    CompanionPairing active = companionPairing.getAndSet(null);
                    if (active != null) active.close();
                    signInStatus.setText("Could not start PC pairing: " + safeMessage(error));
                    showError("Could not start PC sign-in.\n\n" + safeMessage(error));
                }
            });
            options.add(pcSignIn);
            options.add(Box.createVerticalStrut(14));

            JButton mods = button("Manage mods for selected version");
            mods.addActionListener(event -> showModManager(frame, gameDirectory(home,
                    ((LaunchProfile) version.getSelectedItem()).version)));
            options.add(mods);
            JButton credits = button("Credits & sources");
            credits.addActionListener(event -> showCredits(frame));
            options.add(Box.createVerticalStrut(8));
            options.add(credits);
            options.add(Box.createVerticalGlue());
            JPanel optionsCard = new JPanel(new BorderLayout());
            optionsCard.setOpaque(true);
            optionsCard.setBackground(new Color(18, 22, 29, 205));
            optionsCard.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(255, 255, 255, 55), 1),
                    BorderFactory.createEmptyBorder(22, 24, 22, 24)));
            optionsCard.add(options, BorderLayout.CENTER);
            homePage.add(optionsCard, BorderLayout.CENTER);

            JPanel keyboardPage = createNameKeyboard(name, () -> {
                nameLabel.setText("Offline player name: " + name.getText().trim());
                cards.show(pages, "home");
                editName.requestFocusInWindow();
            }, () -> cards.show(pages, "home"), "Choose offline player name",
                    JavaCraftBootstrap::validName, 16);
            pages.add(homePage, "home");
            pages.add(keyboardPage, "keyboard");
            frame.setContentPane(pages);

            JButton launch = button("PLAY MINECRAFT");
            launch.setBackground(ACCENT);
            launch.addActionListener(event -> {
                LaunchProfile selected = (LaunchProfile) version.getSelectedItem();
                if (selected == null) return;
                AuthSession account;
                if (microsoft.isSelected()) {
                    account = signedIn.get();
                    if (account == null) {
                        signInStatus.setText("Sign in above first, or select Play Offline.");
                        return;
                    }
                } else {
                    String chosen = name.getText().trim();
                    if (!validName(chosen)) {
                        showError("Use 3–16 letters, numbers, or underscores for the offline name.");
                        cards.show(pages, "keyboard");
                        return;
                    }
                    account = offlineSession(chosen);
                }
                try {
                    Files.writeString(home.resolve("player-name.txt"), name.getText().trim(), StandardCharsets.UTF_8);
                    JsonObject settings = new JsonObject();
                    settings.addProperty("version", selected.version);
                    settings.addProperty("playerName", name.getText().trim());
                    settings.addProperty("authMode", microsoft.isSelected() ? "microsoft" : "offline");
                    String inputMode = controlMode.getSelectedIndex() == 1 ? "keyboardMouse" : "controller";
                    settings.addProperty("inputMode", inputMode);
                    Files.writeString(home.resolve("launcher-settings.json"), settings.toString(),
                            StandardCharsets.UTF_8);
                    result.set(new LaunchSelection(selected.version, selected.file, account,
                            inputMode, "default"));
                    frame.dispose();
                    finished.countDown();
                } catch (IOException error) {
                    showError("Could not save launcher settings: " + safeMessage(error));
                }
            });
            JPanel footerBar = new JPanel(new BorderLayout());
            footerBar.setOpaque(false);
            footerBar.setBorder(BorderFactory.createEmptyBorder(0, 42, 22, 42));
            JLabel controllerHint = new JLabel("D-pad: move   X: select   O: back",
                    SwingConstants.CENTER);
            controllerHint.setForeground(new Color(235, 235, 240));
            controllerHint.setFont(new Font("SansSerif", Font.PLAIN, 14));
            footerBar.add(controllerHint, BorderLayout.NORTH);
            footerBar.add(launch, BorderLayout.CENTER);
            homePage.add(footerBar, BorderLayout.PAGE_END);

            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) {
                    CompanionPairing active = companionPairing.getAndSet(null);
                    if (active != null) active.close();
                    finished.countDown();
                }
                @Override public void windowClosing(WindowEvent event) {
                    SwingWorker<AuthSession, Void> worker = loginWorker.getAndSet(null);
                    if (worker != null) worker.cancel(true);
                    CompanionPairing active = companionPairing.getAndSet(null);
                    if (active != null) active.close();
                    frame.dispose();
                    finished.countDown();
                }
            });
            frame.setVisible(true);
            installControllerFocusNavigation(frame, keyboardPage);
            version.requestFocusInWindow();
        });
        finished.await();
        return result.get();
    }

    private static void showCredits(JFrame parent) {
        String text = String.join("\n",
                "JavaCraft launcher and PS5 integration: V0BIT",
                "Minecraft Java Edition and assets: owner's official installation",
                "PS5 application base: Prospero Win — https://github.com/mpereiraesaa/prospero-win",
                "Homebrew title discovery: ShadowMountPlus — https://github.com/drakmor/shadowMountPlus",
                "Minecraft loader: Fabric — https://fabricmc.net/",
                "Controller support: Controlify — https://github.com/isXander/Controlify",
                "Configuration and game libraries: YACL and Fabric API",
                "PS5 selection-audio conversion: PS5 AT9 Converter — https://github.com/blackbearreloaded/ps5-at9-converter",
                "Wallpaper: official Minecraft wallpaper collection — https://www.minecraft.net/en-us/collectibles",
                "Selection music: C418, Minecraft; supplied by the owner for this private installation.");
        JTextArea details = new JTextArea(text, 15, 68);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setCaretPosition(0);
        details.setBackground(PANEL);
        details.setForeground(Color.WHITE);
        details.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        JScrollPane scroll = new JScrollPane(details);
        scroll.setPreferredSize(new Dimension(760, 380));
        JOptionPane.showMessageDialog(parent, scroll, "JavaCraft • Credits & sources",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static JPanel createNameKeyboard(JTextField name, Runnable accept, Runnable cancel,
                                             String title, java.util.function.Predicate<String> validator,
                                             int maxLength) {
        JPanel content = new JPanel(new BorderLayout(12, 14));
        content.setBackground(BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(20, 34, 20, 34));
        JLabel heading = new JLabel(title, SwingConstants.CENTER);
        heading.setForeground(Color.WHITE);
        heading.setFont(new Font("SansSerif", Font.BOLD, 26));
        content.add(heading, BorderLayout.NORTH);
        JLabel status = label("D-pad: move    X: select    □: delete    O: back");
        JPanel edit = new JPanel(new BorderLayout(8, 8));
        edit.setOpaque(false);
        edit.add(name, BorderLayout.CENTER);
        edit.add(status, BorderLayout.SOUTH);
        content.add(edit, BorderLayout.CENTER);
        JPanel keyboard = new JPanel(new GridLayout(6, KEY_COLUMNS, 5, 5));
        keyboard.setOpaque(false);
        JButton[] buttons = new JButton[KEYS.length];
        Border normalBorder = BorderFactory.createLineBorder(new Color(72, 79, 91), 1);
        Border selectedBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(196, 170, 255), 3),
                BorderFactory.createLineBorder(ACCENT, 2));
        for (int i = 0; i < KEYS.length; i++) {
            final int index = i;
            JButton button = button(KEYS[i].toLowerCase(java.util.Locale.ROOT));
            button.setFont(new Font("SansSerif", Font.BOLD, 16));
            button.setBorder(normalBorder);
            button.addActionListener(event -> {
                String key = KEYS[index];
                if ("ENTER".equals(key)) {
                    if (!validator.test(name.getText().trim())) {
                        status.setText("The value is incomplete or invalid.");
                        return;
                    }
                    accept.run();
                } else if ("CLEAR".equals(key)) {
                    name.setText("");
                } else if ("DEL".equals(key)) {
                    deleteLast(name);
                } else if (name.getText().length() < maxLength) {
                    String letter = key;
                    letter = key.toLowerCase(java.util.Locale.ROOT);
                    name.setText(name.getText() + letter);
                }
            });
            buttons[i] = button;
            keyboard.add(button);
        }
        content.add(keyboard, BorderLayout.SOUTH);
        java.awt.KeyEventDispatcher dispatcher = event -> {
            if (event.getID() != KeyEvent.KEY_PRESSED || !content.isShowing()) return false;
            int selected = -1;
            for (int i = 0; i < buttons.length; i++)
                if (buttons[i].isFocusOwner()) { selected = i; break; }
            if (selected < 0) return false;
            int next = selected;
            switch (event.getKeyCode()) {
                case KeyEvent.VK_LEFT -> next = selected % KEY_COLUMNS == 0 ? selected : selected - 1;
                case KeyEvent.VK_RIGHT -> next = selected % KEY_COLUMNS == KEY_COLUMNS - 1 || selected + 1 >= KEYS.length
                        ? selected : selected + 1;
                case KeyEvent.VK_UP -> next = selected < KEY_COLUMNS ? selected : selected - KEY_COLUMNS;
                case KeyEvent.VK_DOWN -> next = selected >= KEYS.length - KEY_COLUMNS
                        ? selected : Math.min(selected + KEY_COLUMNS, KEYS.length - 1);
                case KeyEvent.VK_BACK_SPACE -> { deleteLast(name); return true; }
                case KeyEvent.VK_ESCAPE -> { cancel.run(); return true; }
                case KeyEvent.VK_ENTER -> { buttons[selected].doClick(); return true; }
                default -> { return false; }
            }
            buttons[selected].setBorder(normalBorder);
            buttons[next].setBorder(selectedBorder);
            buttons[next].requestFocusInWindow();
            return true;
        };
        java.util.concurrent.atomic.AtomicBoolean dispatcherRegistered = new java.util.concurrent.atomic.AtomicBoolean();
        content.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (content.isShowing()) {
                if (dispatcherRegistered.compareAndSet(false, true))
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
                buttons[0].setBorder(selectedBorder);
                buttons[0].requestFocusInWindow();
            } else if (dispatcherRegistered.compareAndSet(true, false)) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
            }
        });
        return content;
    }

    private static List<LaunchProfile> launchProfiles(Path home) throws IOException {
        List<LaunchProfile> profiles = new ArrayList<>();
        Path directory = home.resolve("launches");
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                files.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(path -> profiles.add(new LaunchProfile(
                                path.getFileName().toString().replaceFirst("\\.json$", ""), path)));
            }
        }
        if (profiles.isEmpty() && Files.isRegularFile(home.resolve("launch.json")))
            profiles.add(new LaunchProfile("26.3", home.resolve("launch.json")));
        profiles.sort(Comparator.comparing(LaunchProfile::version).reversed());
        return profiles;
    }

    /** Installs Mojang's own client, libraries, natives, and assets on first launch. */
    private static void ensureVersionInstalled(Path home, String version, Path launchFile) throws Exception {
        JsonObject launch = JsonParser.parseString(Files.readString(launchFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (versionReady(home, launch)) {
            Files.createDirectories(home.resolve("installed"));
            Files.writeString(home.resolve("installed").resolve(version + ".complete"),
                    "JavaCraft verified install\n", StandardCharsets.UTF_8);
            return;
        }

        Path manifests = home.resolve("manifests");
        Path gameManifest = manifests.resolve("minecraft-" + version + ".json");
        Path fabricManifest = manifests.resolve("fabric-" + version + ".json");
        if (!Files.isRegularFile(gameManifest) || !Files.isRegularFile(fabricManifest))
            throw new IOException("This release is missing the download manifest for Minecraft " + version
                    + ". Rebuild it with tools/build.ps1 before publishing.");

        showInstaller(version);
        try {
            JsonObject minecraft = JsonParser.parseString(Files.readString(gameManifest, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject fabric = JsonParser.parseString(Files.readString(fabricManifest, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!version.equals(required(minecraft, "id")))
                throw new IOException("The official download manifest does not match Minecraft " + version + ".");

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(25))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            Path librariesRoot = home.resolve("libraries");
            Path versionRoot = home.resolve("versions").resolve(version);
            Path nativeRoot = home.resolve("natives").resolve(version);
            Files.createDirectories(versionRoot);
            Files.createDirectories(nativeRoot);

            JsonObject client = minecraft.getAsJsonObject("downloads").getAsJsonObject("client");
            Path clientJar = safeResolve(home, required(launch, "clientJar"));
            downloadVerified(http, required(client, "url"), clientJar,
                    client.get("size").getAsLong(), required(client, "sha1"));
            Path storedVersionMetadata = versionRoot.resolve(version + ".json");
            Files.copy(gameManifest, storedVersionMetadata, StandardCopyOption.REPLACE_EXISTING);

            Map<String, DownloadSpec> librarySources = new HashMap<>();
            List<Path> nativeArchives = new ArrayList<>();
            JsonArray officialLibraries = minecraft.getAsJsonArray("libraries");
            if (officialLibraries != null) {
                for (JsonElement element : officialLibraries) {
                    if (!element.isJsonObject()) continue;
                    JsonObject library = element.getAsJsonObject();
                    if (!allowedWindowsLibrary(library)) continue;
                    JsonObject downloads = library.has("downloads") && library.get("downloads").isJsonObject()
                            ? library.getAsJsonObject("downloads") : new JsonObject();
                    JsonObject artifact = downloads.has("artifact") && downloads.get("artifact").isJsonObject()
                            ? downloads.getAsJsonObject("artifact") : null;
                    if (artifact != null && artifact.has("path")) {
                        String path = required(artifact, "path");
                        DownloadSpec spec = downloadSpec(artifact);
                        if (library.has("name") && library.get("name").getAsString()
                                .endsWith(":natives-windows")) {
                            Path archive = safeResolve(librariesRoot, path);
                            downloadVerified(http, spec.url, archive, spec.size, spec.sha1);
                            nativeArchives.add(archive);
                        } else {
                            librarySources.put(path, spec);
                        }
                    }
                    JsonObject classifiers = downloads.has("classifiers") && downloads.get("classifiers").isJsonObject()
                            ? downloads.getAsJsonObject("classifiers") : new JsonObject();
                    String nativeKey = library.has("natives") && library.get("natives").isJsonObject()
                            ? library.getAsJsonObject("natives").has("windows")
                                ? library.getAsJsonObject("natives").get("windows").getAsString() : null
                            : null;
                    if (nativeKey != null) {
                        nativeKey = nativeKey.replace("${arch}", "64");
                        if (classifiers.has(nativeKey)) {
                            JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeKey);
                            String path = required(nativeArtifact, "path");
                            Path archive = safeResolve(librariesRoot, path);
                            DownloadSpec spec = downloadSpec(nativeArtifact);
                            downloadVerified(http, spec.url, archive, spec.size, spec.sha1);
                            nativeArchives.add(archive);
                        }
                    }
                }
            }

            JsonArray fabricLibraries = fabric.getAsJsonArray("libraries");
            if (fabricLibraries != null) {
                for (JsonElement element : fabricLibraries) {
                    if (!element.isJsonObject()) continue;
                    JsonObject library = element.getAsJsonObject();
                    String path = mavenPath(required(library, "name"));
                    if (!library.has("url")) throw new IOException("Fabric library has no download source: " + path);
                    String artifactUrl = library.get("url").getAsString().replaceAll("/+$", "") + "/" + path;
                    librarySources.put(path, new DownloadSpec(artifactUrl,
                            library.has("size") ? library.get("size").getAsLong() : -1,
                            library.has("sha1") ? library.get("sha1").getAsString().toLowerCase() : ""));
                }
            }

            List<String> requiredLibraries = strings(launch.getAsJsonArray("libraries"));
            int libraryIndex = 0;
            for (String relative : requiredLibraries) {
                Path target = safeResolve(librariesRoot, relative);
                DownloadSpec spec = librarySources.get(relative);
                if (spec == null) throw new IOException("The release is missing download details for library " + relative);
                downloadVerified(http, spec.url, target, spec.size, spec.sha1);
                libraryIndex++;
                if ((libraryIndex % 8) == 0 || libraryIndex == requiredLibraries.size())
                    setInstallerStatus("Preparing Java libraries " + libraryIndex + "/" + requiredLibraries.size());
            }

            for (Path archive : nativeArchives) extractWindowsNatives(archive, nativeRoot);
            downloadAssets(http, home, minecraft.getAsJsonObject("assetIndex"));
            installModpack(home, version);
            Files.createDirectories(home.resolve("installed"));
            Files.writeString(home.resolve("installed").resolve(version + ".complete"),
                    "JavaCraft verified install\n", StandardCharsets.UTF_8);
            setInstallerStatus("Minecraft " + version + " is ready.");
        } catch (Exception error) {
            closeInstaller();
            throw error;
        }
        closeInstaller();
    }

    private static boolean versionReady(Path home, JsonObject launch) {
        try {
            String version = required(launch, "id");
            if (!Files.isRegularFile(safeResolve(home, required(launch, "clientJar")))) return false;
            Path libraryRoot = home.resolve("libraries").normalize();
            for (String relative : strings(launch.getAsJsonArray("libraries")))
                if (!Files.isRegularFile(safeResolve(libraryRoot, relative))) return false;
            String assetIndex = required(launch, "assetIndex");
            Path indexPath = home.resolve("assets").resolve("indexes").resolve(assetIndex + ".json");
            if (!Files.isRegularFile(indexPath)) return false;
            Path natives = home.resolve("natives").resolve(version);
            try (var files = Files.list(natives)) {
                if (!files.anyMatch(path -> path.getFileName().toString().toLowerCase().endsWith(".dll")))
                    return false;
            }
            if (Files.isRegularFile(home.resolve("installed").resolve(version + ".complete"))) return true;
            JsonObject objects = JsonParser.parseString(Files.readString(indexPath, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("objects");
            if (objects == null) return false;
            for (Map.Entry<String, JsonElement> assetEntry : objects.entrySet()) {
                JsonElement assetValue = assetEntry.getValue();
                String hash = required(assetValue.getAsJsonObject(), "hash");
                if (!Files.isRegularFile(home.resolve("assets").resolve("objects")
                        .resolve(hash.substring(0, 2)).resolve(hash))) return false;
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static void downloadAssets(HttpClient http, Path home, JsonObject assetIndexInfo) throws Exception {
        String id = required(assetIndexInfo, "id");
        Path indexPath = home.resolve("assets").resolve("indexes").resolve(id + ".json");
        downloadVerified(http, required(assetIndexInfo, "url"), indexPath,
                assetIndexInfo.get("size").getAsLong(), required(assetIndexInfo, "sha1"));
        JsonObject index = JsonParser.parseString(Files.readString(indexPath, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject objects = index.getAsJsonObject("objects");
        if (objects == null) throw new IOException("The official asset index is incomplete.");
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(objects.entrySet());
        AtomicInteger complete = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> tasks = new ArrayList<>(entries.size());
        for (Map.Entry<String, JsonElement> entry : entries) {
            JsonObject asset = entry.getValue().getAsJsonObject();
            String hash = required(asset, "hash");
            if (!hash.matches("(?i)[0-9a-f]{40}")) throw new IOException("Invalid asset hash in official metadata.");
            Path target = home.resolve("assets").resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            long size = asset.get("size").getAsLong();
            tasks.add(pool.submit(() -> {
                try {
                    downloadVerified(http, "https://resources.download.minecraft.net/" + hash.substring(0, 2)
                            + "/" + hash, target, size, hash);
                    int count = complete.incrementAndGet();
                    if (count == 1 || count % 100 == 0 || count == entries.size())
                        setInstallerStatus("Downloading official Minecraft assets " + count + "/" + entries.size());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(error);
                } catch (IOException error) {
                    throw new CompletionException(error);
                }
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> task : tasks) task.get();
        } catch (Exception error) {
            for (Future<?> task : tasks) task.cancel(true);
            pool.shutdownNow();
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private static void installModpack(Path home, String version) throws IOException {
        installModpack(home, version, gameDirectory(home, version));
    }

    /** Seed package-provided mods without replacing user-added or user-configured files. */
    private static void installModpack(Path home, String version, Path game) throws IOException {
        Path source = home.resolve("modpacks").resolve(version);
        if (!Files.isDirectory(source)) return;
        Files.createDirectories(game);
        Path target = game.resolve("mods");
        Files.createDirectories(target);
        try (var files = Files.list(source)) {
            for (Path jar : files.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                Path destination = target.resolve(jar.getFileName().toString());
                if (!Files.exists(destination)) Files.copy(jar, destination);
            }
        }
        Files.writeString(game.resolve(".javacraft-modpack-installed"), "installed\n", StandardCharsets.UTF_8);
    }

    /** Check the owner's HTTPS feed before Minecraft starts and atomically refresh managed mods. */
    private static void updateManagedMods(Path home, String version, Path game) throws IOException {
        Path packagedMods = home.resolve("modpacks").resolve(version);
        if (!Files.isDirectory(packagedMods)) return;
        Path modsDir = game.resolve("mods");
        Files.createDirectories(modsDir);
        showUpdater(version);
        setInstallerStatus("Checking for JavaCraft mod updates…");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JsonObject manifest;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(MOD_UPDATE_MANIFEST))
                    .timeout(Duration.ofSeconds(25)).header("User-Agent", "JavaCraftPS5/1.0").GET().build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                setInstallerStatus("No update feed published yet; using installed mods.");
                closeInstallerLater();
                return;
            }
            if (response.statusCode() != 200) {
                setInstallerStatus("Update service unavailable; using installed mods.");
                closeInstallerLater();
                return;
            }
            manifest = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            setInstallerStatus("Update check interrupted; using installed mods.");
            closeInstallerLater();
            return;
        } catch (Exception error) {
            setInstallerStatus("Could not reach the update service; using installed mods.");
            closeInstallerLater();
            return;
        }

        try {
            if (!manifest.has("schemaVersion") || manifest.get("schemaVersion").getAsInt() != 1)
                throw new IOException("The JavaCraft mod feed has an unsupported format.");
            JsonObject versions = manifest.has("versions") && manifest.get("versions").isJsonObject()
                    ? manifest.getAsJsonObject("versions") : new JsonObject();
            if (!versions.has(version) || !versions.get(version).isJsonArray()) {
                setInstallerStatus("No updates for Minecraft " + version + "; using installed mods.");
                closeInstallerLater();
                return;
            }
            JsonArray entries = versions.getAsJsonArray(version);
            if (entries.isEmpty()) {
                setInstallerStatus("Mods are up to date.");
                closeInstallerLater();
                return;
            }

            Path statePath = game.resolve(".javacraft-mod-updates.json");
            JsonObject oldState = readModUpdateState(statePath);
            JsonObject oldMods = oldState.has("mods") && oldState.get("mods").isJsonObject()
                    ? oldState.getAsJsonObject("mods") : new JsonObject();
            java.util.Set<String> seenIds = new java.util.HashSet<>();
            java.util.Set<String> seenFiles = new java.util.HashSet<>();
            List<ModUpdate> updates = new ArrayList<>();
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) throw new IOException("The JavaCraft mod feed contains an invalid entry.");
                JsonObject item = element.getAsJsonObject();
                String id = required(item, "id");
                String file = required(item, "file");
                String url = required(item, "url");
                String hash = required(item, "sha256").toLowerCase(Locale.ROOT);
                long size = item.has("size") ? item.get("size").getAsLong() : -1;
                if (!MANAGED_MOD_IDS.contains(id) || !seenIds.add(id))
                    throw new IOException("The mod feed contains an unknown or duplicate mod ID: " + id);
                if (!safeModFileName(file) || !seenFiles.add(file.toLowerCase(Locale.ROOT)))
                    throw new IOException("The mod feed contains an invalid or duplicate mod filename.");
                URI uri;
                try { uri = URI.create(url); }
                catch (IllegalArgumentException error) { throw new IOException("A mod download URL is invalid.", error); }
                if (!"https".equalsIgnoreCase(uri.getScheme()) || !MOD_UPDATE_HOST.equalsIgnoreCase(uri.getHost())
                        || uri.getUserInfo() != null || uri.getPort() != -1
                        || uri.getPath() == null || !uri.getPath().startsWith(MOD_UPDATE_FILE_PREFIX))
                    throw new IOException("A mod update must come from the trusted JavaCraft HTTPS feed.");
                if (!hash.matches("(?i)[0-9a-f]{64}") || size < 1 || size > 128L * 1024L * 1024L)
                    throw new IOException("The mod feed contains an invalid file size or SHA-256 hash.");
                Path target = modsDir.resolve(file).normalize();
                if (!target.getParent().equals(modsDir.toAbsolutePath().normalize())
                        && !target.getParent().equals(modsDir.normalize()))
                    throw new IOException("The mod feed contains an unsafe destination path.");
                String oldFile = trackedModFile(oldMods, id);
                Path oldPackagedFile = packagedMods.resolve(file);
                if (Files.exists(target) && !Files.isRegularFile(target))
                    throw new IOException("A mod destination is not a regular file: " + file);
                if (Files.exists(target) && !Files.exists(oldPackagedFile)
                        && !file.equals(oldFile))
                    throw new IOException("Refusing to replace an untracked user mod: " + file);
                updates.add(new ModUpdate(id, file, uri, size, hash, target, oldFile));
            }

            Path stagingRoot = home.resolve("updates").resolve("staging").resolve(version);
            Files.createDirectories(stagingRoot);
            List<ModUpdate> changed = new ArrayList<>();
            int checked = 0;
            for (ModUpdate update : updates) {
                checked++;
                if (Files.isRegularFile(update.target) && sha256(update.target).equals(update.sha256)) {
                    setInstallerStatus("Checking mods " + checked + "/" + updates.size());
                    continue;
                }
                Path staged = stagingRoot.resolve(update.file + ".part");
                Files.deleteIfExists(staged);
                downloadModVerified(http, update, staged);
                update.staged = staged;
                changed.add(update);
                setInstallerStatus("Downloading mod updates " + changed.size() + "/" + updates.size());
            }

            Path backupRoot = stagingRoot.resolve("backup");
            Files.createDirectories(backupRoot);
            Map<Path, Path> backups = new java.util.LinkedHashMap<>();
            List<Path> newlyCreated = new ArrayList<>();
            try {
                for (ModUpdate update : changed) {
                    backupFileIfPresent(update.target, backupRoot, backups);
                    moveReplace(update.staged, update.target);
                    if (!backups.containsKey(update.target)) newlyCreated.add(update.target);
                }
                // Remove only old filenames recorded as JavaCraft-managed for this exact mod ID.
                for (ModUpdate update : updates) {
                    if (update.oldFile == null || update.oldFile.equals(update.file)) continue;
                    Path previous = modsDir.resolve(update.oldFile).normalize();
                    if (!previous.getParent().equals(modsDir.toAbsolutePath().normalize())
                            && !previous.getParent().equals(modsDir.normalize())) continue;
                    if (Files.isRegularFile(previous)) {
                        backupFileIfPresent(previous, backupRoot, backups);
                        Files.deleteIfExists(previous);
                    }
                }
                JsonObject newState = new JsonObject();
                newState.addProperty("schemaVersion", 1);
                JsonObject newManaged = new JsonObject();
                for (ModUpdate update : updates) {
                    JsonObject record = new JsonObject();
                    record.addProperty("file", update.file);
                    record.addProperty("sha256", update.sha256);
                    newManaged.add(update.id, record);
                }
                newState.add("mods", newManaged);
                writeJsonAtomically(statePath, newState);
            } catch (Exception error) {
                for (Map.Entry<Path, Path> backup : backups.entrySet()) {
                    try { Files.copy(backup.getValue(), backup.getKey(), StandardCopyOption.REPLACE_EXISTING); }
                    catch (IOException ignored) { }
                }
                for (Path created : newlyCreated) {
                    if (!backups.containsKey(created)) try { Files.deleteIfExists(created); }
                    catch (IOException ignored) { }
                }
                if (error instanceof IOException io) throw io;
                throw new IOException("Could not apply JavaCraft mod updates safely.", error);
            } finally {
                for (ModUpdate update : changed) Files.deleteIfExists(update.staged);
            }
            for (Path backup : backups.values()) {
                try { Files.deleteIfExists(backup); } catch (IOException ignored) { }
            }
            setInstallerStatus(changed.isEmpty() ? "Mods are up to date."
                    : "Updated " + changed.size() + " mod(s). Starting Minecraft…");
        } catch (IOException error) {
            closeInstallerLater();
            throw error;
        } catch (RuntimeException error) {
            closeInstallerLater();
            throw new IOException("The JavaCraft mod update feed is invalid.", error);
        }
        closeInstallerLater();
    }

    private static JsonObject readModUpdateState(Path path) {
        if (!Files.isRegularFile(path)) return new JsonObject();
        try { return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception ignored) { return new JsonObject(); }
    }

    private static String trackedModFile(JsonObject state, String id) {
        try {
            JsonObject item = state.getAsJsonObject(id);
            String file = item.get("file").getAsString();
            return safeModFileName(file) ? file : null;
        } catch (Exception ignored) { return null; }
    }

    private static boolean safeModFileName(String file) {
        return file != null && file.length() <= 128 && file.toLowerCase(Locale.ROOT).endsWith(".jar")
                && file.matches("[A-Za-z0-9][A-Za-z0-9 ._+()\\-]{0,123}\\.jar")
                && !file.contains("..") && !file.endsWith(".");
    }

    private static void downloadModVerified(HttpClient http, ModUpdate update, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        HttpRequest request = HttpRequest.newBuilder(update.url).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "JavaCraftPS5/1.0").GET().build();
        HttpResponse<java.io.InputStream> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Mod update was interrupted.", error);
        } catch (Exception error) { throw new IOException("Could not download mod " + update.file + ".", error); }
        try (var body = response.body(); var output = Files.newOutputStream(target)) {
            if (response.statusCode() != 200)
                throw new IOException("Mod download returned HTTP " + response.statusCode() + ": " + update.file);
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = body.read(buffer)) != -1) {
                total += count;
                if (total > update.size) throw new IOException("Mod download exceeded its declared size: " + update.file);
                output.write(buffer, 0, count);
            }
            if (total != update.size || !sha256(target).equals(update.sha256))
                throw new IOException("Mod update failed its SHA-256 integrity check: " + update.file);
        } catch (IOException error) {
            Files.deleteIfExists(target);
            throw error;
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException("SHA-256 is unavailable.", error); }
    }

    private static void backupFileIfPresent(Path target, Path backupRoot, Map<Path, Path> backups) throws IOException {
        if (!Files.isRegularFile(target) || backups.containsKey(target)) return;
        Path backup = backupRoot.resolve(Integer.toHexString(target.toString().hashCode()) + "-" + target.getFileName());
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        backups.put(target, backup);
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeJsonAtomically(Path target, JsonObject value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(temporary, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(value),
                StandardCharsets.UTF_8);
        moveReplace(temporary, target);
    }

    private static void showUpdater(String version) throws IOException {
        try { showInstaller(version); }
        catch (Exception error) { throw new IOException("Could not show the JavaCraft update status.", error); }
    }

    private static void closeInstallerLater() { closeInstaller(); }

    private static void extractWindowsNatives(Path archivePath, Path target) throws IOException {
        Files.createDirectories(target);
        try (ZipFile archive = new ZipFile(archivePath.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = Path.of(entry.getName()).getFileName().toString();
                if (!entry.isDirectory() && name.toLowerCase().endsWith(".dll")) {
                    Path destination = target.resolve(name);
                    if (!Files.exists(destination))
                        try (var input = archive.getInputStream(entry)) { Files.copy(input, destination); }
                }
            }
        }
    }

    private static boolean allowedWindowsLibrary(JsonObject library) {
        if (!library.has("rules") || !library.get("rules").isJsonArray()) return true;
        boolean allowed = false;
        for (JsonElement element : library.getAsJsonArray("rules")) {
            if (!element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();
            JsonObject os = rule.has("os") && rule.get("os").isJsonObject()
                    ? rule.getAsJsonObject("os") : new JsonObject();
            String name = os.has("name") ? os.get("name").getAsString() : "windows";
            String arch = os.has("arch") ? os.get("arch").getAsString() : "";
            boolean matches = name.equals("windows") && !arch.equals("x86")
                    && (arch.isBlank() || arch.equals("x86_64") || arch.equals("amd64"));
            if (matches) allowed = rule.has("action") && rule.get("action").getAsString().equals("allow");
        }
        return allowed;
    }

    private static DownloadSpec downloadSpec(JsonObject item) throws IOException {
        return downloadSpec(item, required(item, "url"));
    }

    private static DownloadSpec downloadSpec(JsonObject item, String fallbackUrl) throws IOException {
        String url = item.has("url") ? item.get("url").getAsString() : fallbackUrl;
        long size = item.has("size") ? item.get("size").getAsLong() : -1;
        String sha1 = item.has("sha1") ? item.get("sha1").getAsString() : "";
        if (!url.startsWith("https://") || !sha1.matches("(?i)[0-9a-f]{40}"))
            throw new IOException("A download entry is missing a secure URL or SHA-1 hash.");
        return new DownloadSpec(url, size, sha1.toLowerCase());
    }

    private static void downloadVerified(HttpClient http, String url, Path target, long size, String sha1)
            throws IOException, InterruptedException {
        if (Files.isRegularFile(target) && validDownload(target, size, sha1)) return;
        if (!url.startsWith("https://")) throw new IOException("Refusing a non-HTTPS game download.");
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "JavaCraftPS5/1.0").GET().build();
        HttpResponse<java.io.InputStream> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException error) { throw error; }
        catch (Exception error) { throw new IOException("Could not download " + target.getFileName() + ".", error); }
        try (var body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IOException("Download failed with HTTP " + response.statusCode() + ": " + target.getFileName());
            Files.copy(body, temporary, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
        if (!validDownload(temporary, size, sha1)) {
            Files.deleteIfExists(temporary);
            throw new IOException("Downloaded file failed its Mojang checksum: " + target.getFileName());
        }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean validDownload(Path path, long size, String sha1) {
        try {
            if (size >= 0 && Files.size(path) != size) return false;
            if (sha1 == null || sha1.isBlank()) return size >= 0;
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha1);
        } catch (Exception ignored) { return false; }
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path result = base.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!result.startsWith(base)) throw new IOException("Unsafe relative download path in release metadata.");
        return result;
    }

    private static String mavenPath(String coordinate) throws IOException {
        String[] parts = coordinate.split(":", -1);
        if (parts.length < 3 || parts.length > 4) throw new IOException("Invalid Fabric library coordinate.");
        String artifactVersion = parts[2];
        String extension = "jar";
        int at = artifactVersion.lastIndexOf('@');
        if (at >= 0) {
            extension = artifactVersion.substring(at + 1);
            artifactVersion = artifactVersion.substring(0, at);
        }
        String classifier = parts.length == 4 ? "-" + parts[3] : "";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + artifactVersion + "/"
                + parts[1] + "-" + artifactVersion + classifier + "." + extension;
    }

    private static void showInstaller(String version) throws Exception {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("JavaCraft — first-time setup");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setSize(720, 170);
            frame.setResizable(false);
            frame.setLocationRelativeTo(null);
            JLabel title = new JLabel("Preparing Minecraft " + version, SwingConstants.CENTER);
            title.setForeground(Color.WHITE);
            title.setFont(new Font("SansSerif", Font.BOLD, 21));
            installerStatus = new JLabel("Downloading files from the official Minecraft servers…", SwingConstants.CENTER);
            installerStatus.setForeground(new Color(215, 220, 231));
            installerStatus.setFont(new Font("SansSerif", Font.PLAIN, 16));
            JPanel panel = new JPanel(new GridLayout(2, 1, 6, 8));
            panel.setBackground(BACKGROUND);
            panel.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
            panel.add(title);
            panel.add(installerStatus);
            frame.setContentPane(panel);
            frame.setVisible(true);
            installerFrame = frame;
        });
    }

    private static void setInstallerStatus(String value) {
        JLabel label = installerStatus;
        if (label != null) SwingUtilities.invokeLater(() -> label.setText(value));
    }

    private static void closeInstaller() {
        JFrame frame = installerFrame;
        installerFrame = null;
        installerStatus = null;
        if (frame != null) SwingUtilities.invokeLater(frame::dispose);
    }

    private static JsonObject readLauncherSettings(Path home) {
        Path path = home.resolve("launcher-settings.json");
        if (!Files.isRegularFile(path)) return new JsonObject();
        try { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
        catch (Exception ignored) { return new JsonObject(); }
    }

    private static Path gameDirectory(Path home, String version) {
        return "26.3".equals(version) ? home.resolve("game")
                : home.resolve("game").resolve("instances").resolve(version);
    }

    private static Path resolveGameDirectory(Path home, String version, String instanceId)
            throws IOException {
        if (instanceId == null || instanceId.isBlank() || "default".equals(instanceId))
            return gameDirectory(home, version);
        if (!instanceId.matches("[a-z0-9_-]{1,31}"))
            throw new IOException("The selected JavaCraft instance name is invalid.");
        Path root = home.resolve("game").resolve("java-instances").toAbsolutePath().normalize();
        Path instance = root.resolve(instanceId).normalize();
        if (!instance.startsWith(root) || Files.isSymbolicLink(instance) || !Files.isDirectory(instance))
            throw new IOException("The selected JavaCraft instance is missing. Return to the launcher and choose another instance.");
        Path versionFile = instance.resolve("version.txt");
        if (Files.isSymbolicLink(versionFile) || !Files.isRegularFile(versionFile))
            throw new IOException("The selected JavaCraft instance has no version marker.");
        String instanceVersion = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
        if (!version.equals(instanceVersion))
            throw new IOException("This instance belongs to Minecraft " + instanceVersion
                    + ". Select that version or choose Default.");
        return instance;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(220, 224, 231));
        label.setFont(new Font("SansSerif", Font.PLAIN, 18));
        label.setBorder(BorderFactory.createEmptyBorder(7, 0, 7, 0));
        return label;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("SansSerif", Font.BOLD, 19));
        button.setForeground(Color.WHITE);
        button.setBackground(PANEL);
        button.setFocusPainted(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(83, 91, 105), 1),
                BorderFactory.createEmptyBorder(9, 14, 9, 14)));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        return button;
    }

    private static void configureTextField(JTextField field, int size) {
        field.setHorizontalAlignment(SwingConstants.CENTER);
        field.setFont(new Font("SansSerif", Font.BOLD, size));
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setBackground(PANEL);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(83, 91, 105), 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
    }

    private static void installControllerFocusNavigation(JFrame frame, JPanel nameKeyboard) {
        KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyEventDispatcher dispatcher = event -> {
            if (event.getID() != KeyEvent.KEY_PRESSED || !frame.isShowing()
                    || manager.getActiveWindow() != frame
                    || nameKeyboard.isShowing()) return false;
            int key = event.getKeyCode();
            if (key != KeyEvent.VK_UP && key != KeyEvent.VK_DOWN
                    && key != KeyEvent.VK_LEFT && key != KeyEvent.VK_RIGHT) return false;
            Component focus = manager.getFocusOwner();
            if (focus == null) return false;
            if (focus instanceof JComboBox<?> combo && combo.isPopupVisible()) return false;
            if (focus instanceof JRadioButton) return false;
            if (key == KeyEvent.VK_UP || key == KeyEvent.VK_LEFT) focus.transferFocusBackward();
            else focus.transferFocus();
            return true;
        };
        manager.addKeyEventDispatcher(dispatcher);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                manager.removeKeyEventDispatcher(dispatcher);
            }
        });
    }

    private static void showModManager(JFrame owner, Path gameDir) {
        Path mods = gameDir.resolve("mods");
        try { Files.createDirectories(mods); }
        catch (IOException error) { showError("Could not open this version's mods folder: " + safeMessage(error)); return; }
        JDialog dialog = new JDialog(owner, "JavaCraft mods", true);
        dialog.setSize(760, 560);
        dialog.setLocationRelativeTo(owner);
        dialog.getContentPane().setBackground(BACKGROUND);
        DefaultListModel<ModEntry> model = new DefaultListModel<>();
        JList<ModEntry> list = new JList<>(model);
        list.setFont(new Font("SansSerif", Font.PLAIN, 18));
        list.setBackground(PANEL);
        list.setForeground(Color.WHITE);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Runnable refresh = () -> {
            model.clear();
            try (var files = Files.list(mods)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().matches(".*\\.jar(\\.disabled)?$"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                        .forEach(path -> {
                            String modId;
                            try { modId = fabricModId(path); }
                            catch (IOException ignored) { modId = ""; }
                            model.addElement(new ModEntry(path,
                                    !path.getFileName().toString().endsWith(".disabled"),
                                    isLockedJavaCraftMod(modId)));
                        });
            } catch (IOException error) {
                showError("Could not list mods: " + safeMessage(error));
            }
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        refresh.run();
        JLabel info = label("Mods are stored separately for each Minecraft version.");
        info.setFont(new Font("SansSerif", Font.PLAIN, 15));
        JPanel actions = new JPanel(new GridLayout(1, 3, 10, 0));
        actions.setOpaque(false);
        JButton add = button("Add mod JAR");
        JButton toggle = button("Enable / disable");
        JButton close = button("Close");
        add.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose a Fabric mod JAR");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Java mod (*.jar)", "jar"));
            if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            Path source = chooser.getSelectedFile().toPath();
            try {
                String modId = fabricModId(source);
                if (isLockedJavaCraftMod(modId))
                    throw new IOException("This JavaCraft component is managed and cannot be added or replaced here.");
                try (var files = Files.list(mods)) {
                    boolean duplicate = files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                            .anyMatch(path -> {
                                try { return modId.equals(fabricModId(path)); }
                                catch (IOException ignored) { return false; }
                            });
                    if (duplicate) throw new IOException("That Fabric mod ID is already enabled in this version.");
                }
                Path target = mods.resolve(source.getFileName().toString());
                if (Files.exists(target)) throw new IOException("A mod with that file name is already installed.");
                Files.copy(source, target);
                refresh.run();
            } catch (Exception error) {
                showError("Could not add this mod: " + safeMessage(error));
            }
        });
        toggle.addActionListener(event -> {
            ModEntry selected = list.getSelectedValue();
            if (selected == null) return;
            if (selected.locked && selected.enabled) {
                showError("This JavaCraft component is locked and cannot be disabled.");
                return;
            }
            String filename = selected.path.getFileName().toString();
            if (!selected.enabled) {
                try {
                    String modId = fabricModId(selected.path);
                    try (var files = Files.list(mods)) {
                        boolean duplicate = files.filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                                .filter(path -> !path.equals(selected.path))
                                .anyMatch(path -> {
                                    try { return modId.equals(fabricModId(path)); }
                                    catch (IOException ignored) { return false; }
                                });
                        if (duplicate) {
                            showError("A mod with this ID is already enabled. Disable it before enabling this file.");
                            return;
                        }
                    }
                } catch (IOException error) {
                    showError("Could not read this mod: " + safeMessage(error));
                    return;
                }
            }
            Path target = selected.enabled
                    ? selected.path.resolveSibling(filename + ".disabled")
                    : selected.path.resolveSibling(filename.substring(0, filename.length() - ".disabled".length()));
            try {
                if (Files.exists(target)) throw new IOException("The target mod file already exists.");
                Files.move(selected.path, target, StandardCopyOption.ATOMIC_MOVE);
                refresh.run();
            } catch (IOException error) {
                showError("Could not change mod state: " + safeMessage(error));
            }
        });
        close.addActionListener(event -> dialog.dispose());
        actions.add(add);
        actions.add(toggle);
        actions.add(close);
        JPanel content = new JPanel(new BorderLayout(12, 14));
        content.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        content.setBackground(BACKGROUND);
        content.add(info, BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setVisible(true);
    }

    private static String fabricModId(Path jar) throws IOException {
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            var entry = archive.getEntry("fabric.mod.json");
            if (entry == null) throw new IOException("This JAR does not contain fabric.mod.json.");
            try (var reader = new java.io.InputStreamReader(archive.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject metadata = JsonParser.parseReader(reader).getAsJsonObject();
                String id = required(metadata, "id");
                if (!id.matches("[a-z0-9_.-]+")) throw new IOException("This mod has an invalid Fabric ID.");
                return id;
            }
        } catch (IllegalStateException error) {
            throw new IOException("This JAR does not contain valid Fabric mod metadata.", error);
        }
    }

    private static boolean isLockedJavaCraftMod(String id) {
        String normalized = id.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.equals("javacraftaccount") || normalized.equals("blackshieldcommunity")
                || normalized.equals("controlify") || normalized.equals("controlifyjavacraft");
    }

    private static AuthSession microsoftSignIn(String clientId) throws Exception {
        return microsoftSignIn(clientId, prompt -> {
            try { showDeviceCode(prompt.verificationUri(), prompt.verificationUriComplete(), prompt.userCode()); }
            catch (Exception error) { throw new CompletionException(error); }
        });
    }

    private static AuthSession microsoftSignIn(String clientId,
                                               Consumer<DeviceCodePrompt> onDeviceCode) throws Exception {
        if (clientId == null || !clientId.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IOException("Enter a valid Microsoft Application Client ID first.");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        JsonObject deviceRequest = new JsonObject();
        deviceRequest.addProperty("client_id", clientId);
        deviceRequest.addProperty("scope", "XboxLive.signin offline_access");
        JsonObject device = postForm(client,
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", deviceRequest);
        String deviceCode = required(device, "device_code");
        String userCode = required(device, "user_code");
        String verificationUri = device.has("verification_uri")
                ? device.get("verification_uri").getAsString() : "https://microsoft.com/devicelogin";
        String verificationUriComplete = device.has("verification_uri_complete")
                ? device.get("verification_uri_complete").getAsString() : verificationUri;
        int interval = device.has("interval") ? device.get("interval").getAsInt() : 5;
        int expiresIn = device.has("expires_in") ? device.get("expires_in").getAsInt() : 900;
        onDeviceCode.accept(new DeviceCodePrompt(verificationUri, verificationUriComplete,
                userCode, expiresIn));

        long deadline = System.nanoTime() + Duration.ofSeconds(expiresIn).toNanos();
        JsonObject token = null;
        while (System.nanoTime() < deadline) {
            Thread.sleep(Math.max(1, interval) * 1000L);
            JsonObject poll = new JsonObject();
            poll.addProperty("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            poll.addProperty("client_id", clientId);
            poll.addProperty("device_code", deviceCode);
            try {
                token = postForm(client, "https://login.microsoftonline.com/consumers/oauth2/v2.0/token", poll);
                break;
            } catch (IOException error) {
                String detail = error.getMessage();
                if (detail.contains("authorization_pending")) continue;
                if (detail.contains("slow_down")) { interval += 5; continue; }
                if (detail.contains("expired_token")) throw new IOException("Microsoft sign-in code expired. Try again.");
                throw error;
            }
        }
        if (token == null) throw new IOException("Microsoft sign-in timed out. Try again.");
        String msaToken = required(token, "access_token");

        JsonObject xblProperties = new JsonObject();
        xblProperties.addProperty("AuthMethod", "RPS");
        xblProperties.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProperties.addProperty("RpsTicket", "d=" + msaToken);
        JsonObject xblBody = new JsonObject();
        xblBody.add("Properties", xblProperties);
        xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblBody.addProperty("TokenType", "JWT");
        JsonObject xbl = postJson(client, "https://user.auth.xboxlive.com/user/authenticate", xblBody);
        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(required(xbl, "Token"));
        xstsProperties.add("UserTokens", userTokens);
        JsonObject xstsBody = new JsonObject();
        xstsBody.add("Properties", xstsProperties);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");
        JsonObject xsts = postJson(client, "https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody);
        String xstsToken = required(xsts, "Token");
        JsonObject claim = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                .get(0).getAsJsonObject();
        String uhs = required(claim, "uhs");
        String xuid = claim.has("xid") ? claim.get("xid").getAsString() : "0";
        JsonObject mcBody = new JsonObject();
        mcBody.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonObject minecraft = postJson(client,
                "https://api.minecraftservices.com/authentication/login_with_xbox", mcBody);
        String minecraftToken = required(minecraft, "access_token");

        JsonObject entitlements = getJson(client, "https://api.minecraftservices.com/entitlements/mcstore", minecraftToken);
        if (!entitlements.has("items") || entitlements.getAsJsonArray("items").isEmpty())
            throw new IOException("This Microsoft account does not own Minecraft Java Edition.");
        JsonObject profile = getJson(client, "https://api.minecraftservices.com/minecraft/profile", minecraftToken);
        return new AuthSession(required(profile, "name"), required(profile, "id"),
                minecraftToken, xuid, "msa", clientId);
    }

    /** Device-code sign-in for the in-game JavaCraft account screen. Tokens stay in memory. */
    public static CompletableFuture<AuthSession> microsoftSignInFromMinecraft(
            String clientId, Consumer<DeviceCodePrompt> onDeviceCode) {
        CompletableFuture<AuthSession> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try { result.complete(microsoftSignIn(clientId, onDeviceCode)); }
            catch (Throwable error) { result.completeExceptionally(error); }
        }, "javacraft-microsoft-sign-in");
        worker.setDaemon(true);
        result.whenComplete((value, error) -> { if (result.isCancelled()) worker.interrupt(); });
        worker.start();
        return result;
    }

    /** Client IDs are public app identifiers, not account secrets. */
    public static String savedMicrosoftClientId() {
        Path home = Path.of(System.getProperty("javacraft.home", "C:\\JavaCraftPS5"))
                .toAbsolutePath().normalize();
        Path file = home.resolve("microsoft-client-id.txt");
        try {
            String value = Files.isRegularFile(file)
                    ? Files.readString(file, StandardCharsets.UTF_8).trim()
                    : System.getenv().getOrDefault("JAVACRAFT_MS_CLIENT_ID", "").trim();
            return value.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                    ? value : DEFAULT_MICROSOFT_CLIENT_ID;
        } catch (IOException ignored) { return DEFAULT_MICROSOFT_CLIENT_ID; }
    }

    public static void saveMicrosoftClientId(String clientId) throws IOException {
        if (clientId == null || !clientId.trim().matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IOException("Enter the Microsoft Application Client ID in GUID format.");
        Path home = Path.of(System.getProperty("javacraft.home", "C:\\JavaCraftPS5"))
                .toAbsolutePath().normalize();
        Files.createDirectories(home);
        Files.writeString(home.resolve("microsoft-client-id.txt"), clientId.trim(),
                StandardCharsets.UTF_8);
    }

    private static void showDeviceCode(String verificationUri, String verificationUriComplete,
                                       String userCode) throws Exception {
        boolean opened = openMicrosoftSignInPage(verificationUriComplete);
        String message = opened
                ? "Microsoft's sign-in page has been opened. Complete sign-in in the browser."
                : "On a phone or computer, open:\n" + verificationUri
                    + "\n\nEnter this one-time code:\n" + userCode;
        message += "\n\nComplete any password, 6-digit security code, or Authenticator prompt on Microsoft's page."
                + "\n\nJavaCraft waits here while Microsoft verifies the account.";
        String dialogMessage = message;
        SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null, dialogMessage,
                "Sign in to Microsoft", JOptionPane.INFORMATION_MESSAGE));
    }

    /** Opens Microsoft's device-login page in the platform browser when available. */
    public static boolean openMicrosoftSignInPage(String address) {
        try {
            URI target = URI.create(address);
            String host = target.getHost();
            if (!"https".equalsIgnoreCase(target.getScheme()) || host == null
                    || !(host.equalsIgnoreCase("microsoft.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".microsoft.com"))) return false;
            if (!Desktop.isDesktopSupported()) return false;
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) return false;
            desktop.browse(target);
            return true;
        } catch (Exception | LinkageError error) {
            return false;
        }
    }

    private static BufferedImage readLauncherBackground(Path home) {
        Path image = home.resolve("bootstrap").resolve("minecraft-title-background.png");
        try { return Files.isRegularFile(image) ? ImageIO.read(image.toFile()) : null; }
        catch (IOException ignored) { return null; }
    }

    private static final class MinecraftTitlePanel extends JPanel {
        private final BufferedImage backgroundImage;

        private MinecraftTitlePanel(BufferedImage backgroundImage) {
            this.backgroundImage = backgroundImage;
            setOpaque(true);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                if (backgroundImage != null) {
                    double scale = Math.max((double) getWidth() / backgroundImage.getWidth(),
                            (double) getHeight() / backgroundImage.getHeight());
                    int width = (int) Math.ceil(backgroundImage.getWidth() * scale);
                    int height = (int) Math.ceil(backgroundImage.getHeight() * scale);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(backgroundImage, (getWidth() - width) / 2,
                            (getHeight() - height) / 2, width, height, null);
                } else {
                    g.setColor(BACKGROUND);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
                g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 155),
                        0, getHeight(), new Color(0, 0, 0, 105)));
                g.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                g.dispose();
            }
        }
    }

    /** Provides a right-stick pointer for the pre-launch Swing screen using the staged SDL3 controller API. */
    private static final class ControllerMouse {
        private static final int SDL_AXIS_RIGHT_X = 2;
        private static final int SDL_AXIS_RIGHT_Y = 3;
        private static final int SDL_BUTTON_SOUTH = 0;
        private static final int SDL_BUTTON_EAST = 1;
        private static final double DEADZONE = 0.18;
        private final JFrame frame;
        private final Robot robot;
        private final javax.swing.Timer timer;
        private final Method pumpEvents, getGamepads, openGamepad, gamepadConnected, getAxis,
                getButton, closeGamepad, freeGamepads, quit;
        private final int quitFlags, buttonSouth, buttonEast;
        private long gamepad;
        private int retryTicks;
        private boolean southWasDown, eastWasDown;
        private int pointerX, pointerY;
        private long lastFrameNanos = System.nanoTime();
        private boolean closed;

        private static ControllerMouse attach(JFrame frame, Path home, List<String> libraries) {
            try { return new ControllerMouse(frame, home, libraries); }
            catch (Throwable ignored) { return null; }
        }

        private ControllerMouse(JFrame frame, Path home, List<String> libraries) throws Exception {
            this.frame = frame;
            this.robot = new Robot();
            Path nativeDirectory = home.resolve("natives").resolve("26.3");
            System.setProperty("org.lwjgl.librarypath", nativeDirectory.toString());
            System.setProperty("java.library.path", nativeDirectory.toString());

            Class<?> init = Class.forName("org.lwjgl.sdl.SDLInit");
            Class<?> gamepadApi = Class.forName("org.lwjgl.sdl.SDLGamepad");
            Class<?> events = Class.forName("org.lwjgl.sdl.SDLEvents");
            Class<?> stdinc = Class.forName("org.lwjgl.sdl.SDLStdinc");
            int initFlags = init.getField("SDL_INIT_JOYSTICK").getInt(null)
                    | init.getField("SDL_INIT_GAMEPAD").getInt(null)
                    | init.getField("SDL_INIT_EVENTS").getInt(null);
            if (!(Boolean) init.getMethod("SDL_Init", int.class).invoke(null, initFlags))
                throw new IOException("SDL gamepad input could not be initialized.");

            this.quitFlags = initFlags;
            this.quit = init.getMethod("SDL_QuitSubSystem", int.class);
            this.getGamepads = gamepadApi.getMethod("SDL_GetGamepads");
            this.openGamepad = gamepadApi.getMethod("SDL_OpenGamepad", int.class);
            this.gamepadConnected = gamepadApi.getMethod("SDL_GamepadConnected", long.class);
            this.getAxis = gamepadApi.getMethod("SDL_GetGamepadAxis", long.class, int.class);
            this.getButton = gamepadApi.getMethod("SDL_GetGamepadButton", long.class, int.class);
            this.closeGamepad = gamepadApi.getMethod("SDL_CloseGamepad", long.class);
            this.freeGamepads = stdinc.getMethod("SDL_free", IntBuffer.class);
            this.pumpEvents = events.getMethod("SDL_PumpEvents");
            this.buttonSouth = gamepadApi.getField("SDL_GAMEPAD_BUTTON_SOUTH").getInt(null);
            this.buttonEast = gamepadApi.getField("SDL_GAMEPAD_BUTTON_EAST").getInt(null);
            connectGamepad();
            this.timer = new javax.swing.Timer(16, event -> tick());
            this.timer.start();
        }

        private void connectGamepad() {
            IntBuffer ids = null;
            try {
                ids = (IntBuffer) getGamepads.invoke(null);
                if (ids == null) return;
                for (int i = 0; i < ids.limit(); i++) {
                    long opened = (long) openGamepad.invoke(null, ids.get(i));
                    if (opened != 0) {
                        gamepad = opened;
                        Point origin = frame.getLocationOnScreen();
                        pointerX = origin.x + frame.getWidth() / 2;
                        pointerY = origin.y + frame.getHeight() / 2;
                        robot.mouseMove(pointerX, pointerY);
                        return;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (ids != null) try { freeGamepads.invoke(null, ids); } catch (Exception ignored) { }
            }
        }

        private void tick() {
            if (closed || !frame.isShowing()) return;
            try {
                pumpEvents.invoke(null);
                if (gamepad == 0) {
                    if (++retryTicks >= 60) { retryTicks = 0; connectGamepad(); }
                    return;
                }
                if (!(Boolean) gamepadConnected.invoke(null, gamepad)) {
                    closeGamepad.invoke(null, gamepad);
                    gamepad = 0;
                    return;
                }
                double x = normalizeAxis((short) getAxis.invoke(null, gamepad, SDL_AXIS_RIGHT_X));
                double y = normalizeAxis((short) getAxis.invoke(null, gamepad, SDL_AXIS_RIGHT_Y));
                long now = System.nanoTime();
                double seconds = Math.min(0.05, (now - lastFrameNanos) / 1_000_000_000.0);
                lastFrameNanos = now;
                if (x != 0 || y != 0) {
                    Point origin = frame.getLocationOnScreen();
                    pointerX = Math.max(origin.x + 4, Math.min(origin.x + frame.getWidth() - 5,
                            pointerX + (int) Math.round(x * 900 * seconds)));
                    pointerY = Math.max(origin.y + 4, Math.min(origin.y + frame.getHeight() - 5,
                            pointerY + (int) Math.round(y * 900 * seconds)));
                    robot.mouseMove(pointerX, pointerY);
                }
                boolean south = (Boolean) getButton.invoke(null, gamepad, buttonSouth);
                if (south && !southWasDown) click();
                southWasDown = south;
                boolean east = (Boolean) getButton.invoke(null, gamepad, buttonEast);
                if (east && !eastWasDown) {
                    robot.keyPress(KeyEvent.VK_ESCAPE);
                    robot.keyRelease(KeyEvent.VK_ESCAPE);
                }
                eastWasDown = east;
            } catch (Exception ignored) {
                gamepad = 0;
            }
        }

        private static double normalizeAxis(short raw) {
            double value = raw / 32767.0;
            if (Math.abs(value) <= DEADZONE) return 0;
            return Math.copySign((Math.abs(value) - DEADZONE) / (1 - DEADZONE), value);
        }

        private void click() {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }

        private void close() {
            closed = true;
            timer.stop();
            try {
                if (gamepad != 0) closeGamepad.invoke(null, gamepad);
                quit.invoke(null, quitFlags);
            } catch (Exception ignored) { }
        }
    }

    private static String localIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> candidates = network.getInetAddresses();
                while (candidates.hasMoreElements()) {
                    InetAddress address = candidates.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) addresses.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return addresses.isEmpty() ? "No local IPv4 address found" : String.join("\n", addresses);
    }

    /** Receives one encrypted, one-use Microsoft session from the PC companion over the LAN. */
    private static final class CompanionPairing implements AutoCloseable {
        private static final int FIRST_PORT = 43821;
        private static final int LAST_PORT = 43830;
        private static final int MAX_BODY = 64 * 1024;
        private static final SecureRandom RANDOM = new SecureRandom();
        private final ServerSocket server;
        private final int port;
        private final String pairingCode;
        private final String expectedClientId;
        private final Consumer<AuthSession> onSession;
        private volatile boolean closed;

        private CompanionPairing(String clientId, String code, Consumer<AuthSession> callback) throws Exception {
            expectedClientId = clientId;
            onSession = callback;
            if (code == null || !code.matches("(?i)[0-9a-f]{32}"))
                throw new IOException("The one-time pairing key is invalid.");
            pairingCode = code.toLowerCase(java.util.Locale.ROOT);
            ServerSocket bound = null;
            int selectedPort = -1;
            for (int candidatePort = FIRST_PORT; candidatePort <= LAST_PORT; candidatePort++) {
                ServerSocket candidate = new ServerSocket();
                try {
                    candidate.setReuseAddress(true);
                    candidate.bind(new java.net.InetSocketAddress(
                            InetAddress.getByName("0.0.0.0"), candidatePort), 4);
                    bound = candidate;
                    selectedPort = candidatePort;
                    break;
                } catch (java.net.BindException occupied) {
                    candidate.close();
                } catch (Exception error) {
                    candidate.close();
                    throw error;
                }
            }
            if (bound == null)
                throw new IOException("JavaCraft sign-in ports " + FIRST_PORT + "-" + LAST_PORT
                        + " are busy. Close another JavaCraft session and try again.");
            server = bound;
            port = selectedPort;
            server.setSoTimeout(5 * 60 * 1000);
        }

        static CompanionPairing start(String clientId, String code, Consumer<AuthSession> callback) throws Exception {
            CompanionPairing pairing = new CompanionPairing(clientId, code, callback);
            Thread receiver = new Thread(pairing::receive, "JavaCraft-PC-pairing");
            receiver.setDaemon(true);
            receiver.start();
            return pairing;
        }

        static CompanionPairing start(String clientId, Consumer<AuthSession> callback) throws Exception {
            byte[] secret = new byte[16];
            RANDOM.nextBytes(secret);
            return start(clientId, HexFormat.of().formatHex(secret), callback);
        }

        private void receive() {
            try {
                for (int attempt = 0; attempt < 4 && !closed; attempt++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(10_000);
                        if (receiveOne(socket)) return;
                    } catch (IOException ignored) {
                        if (closed) return;
                    }
                }
            } finally { close(); }
        }

        private boolean receiveOne(Socket socket) throws IOException {
            var input = new java.io.BufferedInputStream(socket.getInputStream());
            var output = new java.io.BufferedOutputStream(socket.getOutputStream());
            String request = readHttpLine(input);
            if (!"POST /session HTTP/1.1".equals(request) && !"POST /session HTTP/1.0".equals(request)) {
                respond(output, 404, "Not found");
                return false;
            }
            int length = -1;
            String line;
            while (!(line = readHttpLine(input)).isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    try { length = Integer.parseInt(value); }
                    catch (NumberFormatException error) { length = -1; }
                }
                if (name.equalsIgnoreCase("Transfer-Encoding")) length = -1;
            }
            if (length < 1 || length > MAX_BODY) {
                respond(output, 400, "Invalid request size");
                return false;
            }
            byte[] body = input.readNBytes(length);
            if (body.length != length) {
                respond(output, 400, "Incomplete request");
                return false;
            }
            try {
                byte[] encrypted = java.util.Base64.getDecoder().decode(body);
                if (encrypted.length < 12 + 16 + 1 || encrypted.length > MAX_BODY)
                    throw new IOException("Invalid encrypted session.");
                byte[] nonce = Arrays.copyOfRange(encrypted, 0, 12);
                byte[] tag = Arrays.copyOfRange(encrypted, 12, 28);
                byte[] ciphertext = Arrays.copyOfRange(encrypted, 28, encrypted.length);
                byte[] key = MessageDigest.getInstance("SHA-256")
                        .digest(pairingCode.getBytes(StandardCharsets.UTF_8));
                // Java's GCM provider expects the tag appended to ciphertext; rebuild it from the wire format.
                byte[] combined = new byte[ciphertext.length + tag.length];
                System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
                System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce));
                cipher.updateAAD("JavaCraftSessionV1".getBytes(StandardCharsets.UTF_8));
                byte[] clear = cipher.doFinal(combined);
                JsonObject session = JsonParser.parseString(new String(clear, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                String name = required(session, "name");
                String uuid = required(session, "uuid").replace("-", "");
                String token = required(session, "accessToken");
                String xuid = required(session, "xuid");
                String clientId = required(session, "clientId");
                if (!clientId.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                        || (!expectedClientId.isBlank() && !clientId.equalsIgnoreCase(expectedClientId))
                        || !validName(name)
                        || !uuid.matches("(?i)[0-9a-f]{32}") || token.length() < 16
                        || token.length() > 16_384 || !xuid.matches("[0-9]{1,32}"))
                    throw new IOException("The sign-in session did not match this JavaCraft setup.");
                AuthSession account = new AuthSession(name, uuid, token, xuid, "msa", clientId);
                respond(output, 200, "Signed in");
                onSession.accept(account);
                Arrays.fill(clear, (byte) 0);
                Arrays.fill(key, (byte) 0);
                return true;
            } catch (Exception error) {
                respond(output, 403, "Pairing failed");
                return false;
            }
        }

        private static String readHttpLine(java.io.InputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) >= 0) {
                if (value == '\n') break;
                if (value != '\r') line.write(value);
                if (line.size() > 2048) throw new IOException("Request header too long.");
            }
            if (value < 0 && line.size() == 0) throw new IOException("Incomplete request.");
            return line.toString(StandardCharsets.US_ASCII);
        }

        private static void respond(java.io.OutputStream output, int status, String message) throws IOException {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            String phrase = status == 200 ? "OK" : status == 404 ? "Not Found"
                    : status == 400 ? "Bad Request" : "Forbidden";
            output.write(("HTTP/1.1 " + status + " " + phrase + "\r\nContent-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }

        @Override public void close() {
            closed = true;
            try { server.close(); } catch (IOException ignored) { }
        }
    }

    private static JsonObject postForm(HttpClient client, String uri, JsonObject fields) throws Exception {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : fields.entrySet())
            values.add(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                    + java.net.URLEncoder.encode(entry.getValue().getAsString(), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(String.join("&", values))).build();
        return responseJson(client.send(request, HttpResponse.BodyHandlers.ofString()), uri);
    }

    private static JsonObject postJson(HttpClient client, String uri, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return responseJson(client.send(request, HttpResponse.BodyHandlers.ofString()), uri);
    }

    private static JsonObject getJson(HttpClient client, String uri, String bearer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json").GET().build();
        return responseJson(client.send(request, HttpResponse.BodyHandlers.ofString()), uri);
    }

    private static JsonObject responseJson(HttpResponse<String> response, String endpoint) throws IOException {
        JsonObject body;
        try { body = JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (Exception error) { throw new IOException("A sign-in service returned an unreadable response."); }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String code = body.has("error") ? body.get("error").getAsString() : "HTTP " + response.statusCode();
            String description = firstResponseText(body, "error_description", "errorMessage", "Message", "message");
            if (description == null) description = "request rejected";
            String service = URI.create(endpoint).getHost();
            String responsePath = firstResponseText(body, "path");
            String location = service + (responsePath == null ? "" : " " + responsePath);
            if (response.statusCode() == 403 && service.equals("api.minecraftservices.com")
                    && description.toLowerCase(Locale.ROOT).contains("invalid app registration")) {
                throw new IOException("Minecraft Services blocked JavaCraft's Client ID (HTTP 403: Invalid app registration). "
                        + "Microsoft sign-in worked, but Minecraft must approve this app. Request access at https://aka.ms/AppRegInfo.");
            }
            if (code.equals("unauthorized_client") || description.contains("AADSTS700016"))
                throw new IOException("Microsoft rejected this app ID. Use an app registration that allows public device-code sign-in and XboxLive.signin.");
            throw new IOException("Sign-in service rejected the request (" + code + ", " + location + "): " + description);
        }
        return body;
    }

    private static String firstResponseText(JsonObject body, String... keys) {
        for (String key : keys) {
            if (!body.has(key) || body.get(key).isJsonNull()) continue;
            try {
                String value = body.get(key).getAsString().trim();
                if (!value.isEmpty()) return value;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static void deleteLast(JTextField field) {
        String value = field.getText();
        if (!value.isEmpty()) field.setText(value.substring(0, value.length() - 1));
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    private static void restrictPlayerName(JTextField name) {
        ((AbstractDocument) name.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs)
                    throws BadLocationException {
                replace(fb, offset, 0, text, attrs);
            }

            @Override public void replace(FilterBypass fb, int offset, int length, String text,
                                          AttributeSet attrs) throws BadLocationException {
                String value = text == null ? "" : text.replaceAll("[^A-Za-z0-9_]", "");
                int nextLength = fb.getDocument().getLength() - length + value.length();
                if (nextLength <= 16) fb.replace(offset, length, value, attrs);
            }
        });
    }

    private static void launchGame(Path home, JsonObject metadata, AuthSession account,
                                   String selectedVersion, String inputMode,
                                   String instanceId) throws Exception {
        String version = required(metadata, "id");
        if (!version.equals(selectedVersion)) throw new IOException("The selected Minecraft version did not match its launch profile.");
        if (!inputMode.equals("controller") && !inputMode.equals("keyboardMouse"))
            throw new IOException("The selected control mode is invalid.");
        String mainClass = required(metadata, "mainClass");
        Path gameDir = resolveGameDirectory(home, version, instanceId);
        prepareConsoleOptions(gameDir);
        Path assets = home.resolve("assets");
        Path natives = home.resolve("natives").resolve(version);
        Path librariesRoot = home.resolve("libraries").normalize();
        Files.createDirectories(gameDir);
        Files.createDirectories(natives);

        List<String> libraries = strings(metadata.getAsJsonArray("libraries"));
        Path clientJar = home.resolve(required(metadata, "clientJar"));
        List<URL> urls = new ArrayList<>();
        for (String library : libraries) {
            Path path = librariesRoot.resolve(library).normalize();
            if (!path.startsWith(librariesRoot))
                throw new IOException("A Minecraft library path is invalid: " + library);
            try (var input = Files.newInputStream(path)) {
                if (input.read() < 0)
                    throw new IOException("A required Minecraft library is empty: " + library);
            } catch (IOException error) {
                throw new IOException("A required Minecraft library cannot be opened: " + library
                        + " (" + path + ")", error);
            }
            urls.add(path.toUri().toURL());
        }
        try (var input = Files.newInputStream(clientJar)) {
            if (input.read() < 0) throw new IOException("The Minecraft client jar is empty: " + clientJar);
        } catch (IOException error) {
            throw new IOException("The Minecraft client jar cannot be opened: " + clientJar, error);
        }
        urls.add(clientJar.toUri().toURL());

        Map<String, String> variables = new HashMap<>();
        variables.put("auth_player_name", account.name);
        variables.put("version_name", version);
        variables.put("game_directory", gameDir.toString());
        variables.put("assets_root", assets.toString());
        variables.put("assets_index_name", required(metadata, "assetIndex"));
        variables.put("auth_uuid", account.uuid);
        variables.put("auth_access_token", account.accessToken);
        variables.put("auth_xuid", account.xuid);
        variables.put("clientid", account.clientId);
        variables.put("version_type", "release");
        variables.put("resolution_width", "1920");
        variables.put("resolution_height", "1080");
        variables.put("launcher_name", "JavaCraft");
        variables.put("launcher_version", "1.0");
        variables.put("natives_directory", natives.toString());
        variables.put("classpath", buildClasspath(home, libraries, clientJar));

        List<String> args = new ArrayList<>();
        for (String template : strings(metadata.getAsJsonArray("gameArgs"))) {
            String value = substitute(template, variables);
            if (value.contains("${")) continue;
            args.add(value);
        }
        args.add("--userType");
        args.add(account.userType);

        System.setProperty("javacraft.input.controllerOnly", Boolean.toString(inputMode.equals("controller")));
        System.setProperty("javacraft.input.keyboardMouseOnly", Boolean.toString(inputMode.equals("keyboardMouse")));
        System.setProperty("javacraft.keepControllerOnInputSwitch", "true");
        System.setProperty("java.class.path", buildClasspath(home, libraries, clientJar));
        System.setProperty("java.library.path", natives.toString());
        System.setProperty("org.lwjgl.librarypath", natives.toString());
        System.setProperty("jna.tmpdir", natives.resolve("jna").toString());
        System.setProperty("org.lwjgl.system.SharedLibraryExtractPath", natives.resolve("lwjgl").toString());
        System.setProperty("io.netty.native.workdir", natives.resolve("netty").toString());
        System.setProperty("minecraft.launcher.brand", "JavaCraft");
        System.setProperty("minecraft.launcher.version", "1.0");
        // Keep Minecraft libraries and LWJGL in the launch class loader. This
        // avoids a second isolated LWJGL load on the console runtime.
        System.setProperty("fabric.debug.disableClassPathIsolation", "true");
        Thread.currentThread().setContextClassLoader(new URLClassLoader(urls.toArray(URL[]::new),
                JavaCraftBootstrap.class.getClassLoader()));
        Class<?> entry = Class.forName(mainClass, true, Thread.currentThread().getContextClassLoader());
        Method main = entry.getMethod("main", String[].class);
        Path readyMarker = home.resolve(".javacraft-game-ready");
        Files.writeString(readyMarker, "ready", StandardCharsets.UTF_8);
        try {
            main.invoke(null, (Object) args.toArray(String[]::new));
        } catch (InvocationTargetException error) {
            Files.deleteIfExists(readyMarker);
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }

    /**
     * The console was running 32-chunk render and simulation distances. That
     * makes a single-player world open thousands of chunks and exhaust the
     * PS5/Wine file-handle budget while region/entity files are being saved.
     * Keep Minecraft's other user settings intact, save one original copy,
     * and cap only these unusually high values before the game opens options.
     */
    private static void prepareConsoleOptions(Path gameDir) {
        Path options = gameDir.resolve("options.txt");
        if (!Files.isRegularFile(options)) return;
        try {
            List<String> lines = Files.readAllLines(options, StandardCharsets.UTF_8);
            boolean changed = setOptionAtMost(lines, "renderDistance", 12);
            changed |= setOptionAtMost(lines, "simulationDistance", 8);
            if (!changed) return;

            Path backup = gameDir.resolve("options.txt.javacraft-backup");
            if (!Files.exists(backup)) Files.copy(options, backup);
            Path temporary = gameDir.resolve("options.txt.javacraft-tmp");
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, options, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, options, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            System.err.println("[JavaCraft] Could not lower high chunk distances before launch: "
                    + error.getMessage());
        }
    }

    private static boolean setOptionAtMost(List<String> lines, String name, int ceiling) {
        String prefix = name + ":";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith(prefix)) continue;
            try {
                int current = Integer.parseInt(line.substring(prefix.length()).trim());
                if (current > ceiling) {
                    lines.set(i, prefix + ceiling);
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Leave unknown/future option formats untouched.
            }
            return false;
        }
        return false;
    }

    private static String buildClasspath(Path home, List<String> libraries, Path clientJar) {
        List<String> all = new ArrayList<>();
        Path librariesRoot = home.resolve("libraries");
        for (String library : libraries) all.add(librariesRoot.resolve(library).normalize().toString());
        all.add(clientJar.toString());
        return String.join(";", all);
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) result.add(element.getAsString());
        return result;
    }

    private static String required(JsonObject object, String key) throws IOException {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
            throw new IOException("Required Minecraft data is missing: " + key);
        return object.get(key).getAsString();
    }

    private static String substitute(String input, Map<String, String> values) {
        String output = input;
        for (Map.Entry<String, String> entry : values.entrySet())
            output = output.replace("${" + entry.getKey() + "}", entry.getValue());
        return output;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private static void showError(String message) {
        if (java.awt.GraphicsEnvironment.isHeadless()) { System.err.println(message); return; }
        Runnable show = () -> javax.swing.JOptionPane.showMessageDialog(null, message,
                "JavaCraft", javax.swing.JOptionPane.ERROR_MESSAGE);
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(show);
        } catch (Exception ignored) { System.err.println(message); }
    }

    private record LaunchProfile(String version, Path file) {
        @Override public String toString() { return "Minecraft Java " + version; }
    }
    private record LaunchSelection(String version, Path launchFile, AuthSession account,
                                   String inputMode, String instanceId) { }
    private record DownloadSpec(String url, long size, String sha1) { }
    private static final class ModUpdate {
        final String id;
        final String file;
        final URI url;
        final long size;
        final String sha256;
        final Path target;
        final String oldFile;
        Path staged;

        ModUpdate(String id, String file, URI url, long size, String sha256, Path target, String oldFile) {
            this.id = id;
            this.file = file;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
            this.target = target;
            this.oldFile = oldFile;
        }
    }
    public record AuthSession(String name, String uuid, String accessToken, String xuid,
                              String userType, String clientId) { }
    public record DeviceCodePrompt(String verificationUri, String verificationUriComplete,
                                   String userCode, int expiresIn) { }
    private record ModEntry(Path path, boolean enabled, boolean locked) {
        @Override public String toString() {
            return (enabled ? "ON   " : "OFF  ") + path.getFileName() + (locked ? "   [LOCKED]" : "");
        }
    }
}
