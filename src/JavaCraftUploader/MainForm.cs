using System.IO.Compression;
using System.Net;
using System.Text.Json;

namespace JavaCraftUploader;

internal sealed class MainForm : Form
{
    private static readonly Color Canvas = Color.FromArgb(12, 17, 26);
    private static readonly Color Surface = Color.FromArgb(20, 28, 41);
    private static readonly Color Panel2 = Color.FromArgb(27, 37, 53);
    private static readonly Color Line = Color.FromArgb(43, 57, 77);
    private static readonly Color TextMain = Color.FromArgb(238, 243, 249);
    private static readonly Color TextMuted = Color.FromArgb(151, 166, 184);
    private static readonly Color Accent = Color.FromArgb(109, 226, 171);
    private static readonly Color Blue = Color.FromArgb(105, 183, 255);
    private const string AppPath = "/data/homebrew/PPSA99995";
    private const string LibraryPath = "/data/prospero-win";
    private const string PrefixPath = "/data/prospero-win/prefixes/minecraft-java";
    private const string GamePath = PrefixPath + "/drive_c/JavaCraftPS5/game";
    private const string ProfilesPath = LibraryPath + "/profiles/profiles.lst";
    private const string ProfileFile = "minecraft-java.profile";
    private const string TitleId = "PPSA99995";
    private static readonly string SettingsFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "JavaCraft", "uploader.json");

    private readonly TextBox _hostBox = new();
    private readonly TextBox _portBox = new();
    private readonly Label _connectionStatus = new();
    private readonly Label _footerStatus = new();
    private readonly Label _progressText = new();
    private readonly ProgressBar _progressBar = new();
    private readonly Panel _body = new();
    private readonly Button _connectButton = new();
    private Label? _homeWorkflowStatus;
    private Label? _homeInstallTitle;
    private Label? _homeInstallDetail;
    private InstallState _installState;
    private bool _busy;

    private readonly ComboBox _modLocation = new();
    private readonly ListView _modList = new();
    private readonly ListView _worldList = new();
    private Button? _removeModButton;
    private readonly TabControl _manageTabs = new();
    private readonly TextBox _packagePath = new();
    private readonly TextBox _operationLog = new();

    private sealed record SavedSettings(string Host, string Port);
    private sealed record ProgressInfo(string Message, int Percent = -1);
    private sealed record ModChoice(string Label, string Path);
    private sealed record WorldChoice(string Name, string Version, string Path);
    private sealed record InstallPlan(string? AppRoot, string? LibraryRoot, List<UploadFile> Files, string? Error)
    {
        public bool IsValid => AppRoot is not null && LibraryRoot is not null && Error is null;
    }
    private enum InstallState { Unknown, NotInstalled, Installed, RepairRequired }

    public MainForm()
    {
        Text = "JavaCraft PS5 Uploader";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(960, 650);
        Size = new Size(1120, 760);
        BackColor = Canvas;
        ForeColor = TextMain;
        Font = new Font("Segoe UI", 10f);
        AutoScaleMode = AutoScaleMode.Dpi;
        Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Application;

        var top = BuildTopBar();
        top.Dock = DockStyle.Top;
        _body.Dock = DockStyle.Fill;
        _body.Padding = new Padding(28, 24, 28, 14);
        _body.BackColor = Canvas;
        Controls.Add(_body);
        var footer = BuildFooter();
        footer.Dock = DockStyle.Bottom;
        Controls.Add(footer);
        Controls.Add(top);
        FormClosing += (_, _) => SaveSettings();

        LoadSettings();
        ShowHome();
    }

    private Control BuildTopBar()
    {
        var top = new Panel { Height = 86, BackColor = Surface, Padding = new Padding(28, 12, 28, 12) };
        var brand = new TableLayoutPanel { Dock = DockStyle.Left, Width = 360, ColumnCount = 1, RowCount = 3, BackColor = Surface };
        brand.RowStyles.Add(new RowStyle(SizeType.Percent, 48));
        brand.RowStyles.Add(new RowStyle(SizeType.Percent, 30));
        brand.RowStyles.Add(new RowStyle(SizeType.Percent, 22));
        brand.Controls.Add(new Label { Text = "JavaCraft", Font = new Font("Segoe UI Semibold", 19f), ForeColor = TextMain, Dock = DockStyle.Fill, TextAlign = ContentAlignment.BottomLeft }, 0, 0);
        brand.Controls.Add(new Label { Text = "PS5 CONSOLE MANAGER", Font = new Font("Segoe UI Semibold", 8.5f), ForeColor = TextMuted, Dock = DockStyle.Fill, TextAlign = ContentAlignment.TopLeft }, 0, 1);
        var credits = new LinkLabel { Text = "v0bit  ·  BS6 Discord", Font = new Font("Segoe UI Semibold", 8f), LinkColor = Accent, ActiveLinkColor = Accent, VisitedLinkColor = Accent, Dock = DockStyle.Fill, TextAlign = ContentAlignment.TopLeft };
        credits.LinkClicked += (_, _) => System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("https://discord.com/BS6") { UseShellExecute = true });
        brand.Controls.Add(credits, 0, 2);
        top.Controls.Add(brand);

        var connection = new FlowLayoutPanel { Dock = DockStyle.Right, Width = 545, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, BackColor = Surface, Padding = new Padding(0, 12, 0, 0) };
        var hostLabel = SmallLabel("PS5 IP", 48);
        _hostBox.Width = 168;
        StyleTextBox(_hostBox);
        _hostBox.PlaceholderText = "192.168.8.33";
        var portLabel = SmallLabel("FTP", 34);
        _portBox.Width = 65;
        StyleTextBox(_portBox);
        _connectButton.Text = "Connect";
        StyleButton(_connectButton, Accent, Color.FromArgb(12, 30, 25));
        _connectButton.Width = 100;
        _connectButton.Height = 38;
        _connectButton.Margin = new Padding(10, 0, 0, 0);
        _connectButton.Click += async (_, _) => await ConnectAsync();
        _connectionStatus.AutoSize = false;
        _connectionStatus.Width = 86;
        _connectionStatus.Height = 38;
        _connectionStatus.Text = "Not connected";
        _connectionStatus.ForeColor = TextMuted;
        _connectionStatus.TextAlign = ContentAlignment.MiddleRight;
        _connectionStatus.Margin = new Padding(9, 0, 0, 0);
        connection.Controls.AddRange([hostLabel, _hostBox, portLabel, _portBox, _connectButton, _connectionStatus]);
        _hostBox.TextChanged += (_, _) => InvalidateInstallCheck();
        _portBox.TextChanged += (_, _) => InvalidateInstallCheck();
        top.Controls.Add(connection);
        return top;
    }

    private Control BuildFooter()
    {
        var footer = new Panel { Height = 50, BackColor = Surface, Padding = new Padding(28, 8, 28, 8) };
        _footerStatus.Text = "Ready · Connect to the PS5 FTP service on your local network";
        _footerStatus.ForeColor = TextMuted;
        _footerStatus.Dock = DockStyle.Left;
        _footerStatus.Width = 470;
        _progressText.Text = "";
        _progressText.ForeColor = TextMuted;
        _progressText.Dock = DockStyle.Right;
        _progressText.Width = 255;
        _progressText.TextAlign = ContentAlignment.MiddleRight;
        _progressBar.Dock = DockStyle.Right;
        _progressBar.Width = 180;
        _progressBar.Height = 20;
        _progressBar.Style = ProgressBarStyle.Continuous;
        _progressBar.Visible = false;
        footer.Controls.Add(_footerStatus);
        footer.Controls.Add(_progressText);
        footer.Controls.Add(_progressBar);
        return footer;
    }

    private void ShowHome()
    {
        var page = new Panel { Dock = DockStyle.Fill, BackColor = Canvas };
        var intro = new Panel { Dock = DockStyle.Top, Height = 145, BackColor = Canvas };
        intro.Controls.Add(new Label { Text = "Connect, check, then install or repair", Font = new Font("Segoe UI Semibold", 23f), ForeColor = TextMain, AutoSize = true, Location = new Point(2, 5) });
        intro.Controls.Add(new Label { Text = "Step 1: enter the PS5 IP above and select Connect.", Font = new Font("Segoe UI", 11f), ForeColor = TextMuted, AutoSize = true, Location = new Point(4, 52) });
        _homeWorkflowStatus = new Label { Text = "Step 2: the installation check appears here after connecting.", Font = new Font("Segoe UI Semibold", 10f), ForeColor = Blue, AutoSize = true, Location = new Point(4, 82) };
        intro.Controls.Add(_homeWorkflowStatus);
        var note = new Label { Text = "FTP must be enabled on the PS5. Use this tool only on a trusted local network; FTP traffic is not encrypted.", Font = new Font("Segoe UI", 9f), ForeColor = Color.FromArgb(236, 191, 105), AutoSize = true, Location = new Point(4, 111) };
        intro.Controls.Add(note);
        var cards = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1, Padding = new Padding(0, 14, 0, 30), BackColor = Canvas };
        cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.333f));
        cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.333f));
        cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.333f));
        cards.Controls.Add(CreateCard("01", "Manage Mods & Worlds", "Add or remove user mods, back up worlds, or remove a world. JavaCraft and Black Shield files are locked.", Blue, ShowManage, out _, out _), 0, 0);
        cards.Controls.Add(CreateCard("02", "Install JavaCraft", "Upload the JavaCraft app and its complete local PS5 package.", Accent, ShowInstall, out var installTitle, out var installDetail), 1, 0);
        _homeInstallTitle = installTitle;
        _homeInstallDetail = installDetail;
        cards.Controls.Add(CreateCard("03", "Uninstall from PS5", "Remove JavaCraft and its Minecraft data from this console.", Color.FromArgb(255, 145, 135), ShowUninstall, out _, out _), 2, 0);
        page.Controls.Add(cards);
        page.Controls.Add(intro);
        UpdateHomeInstallOverview();
        SetPage(page);
    }

    private Panel CreateCard(string number, string title, string detail, Color accent, Action click, out Label titleLabel, out Label detailLabel)
    {
        var card = new Panel { Dock = DockStyle.Fill, BackColor = Surface, Margin = new Padding(0, 4, 16, 4), Padding = new Padding(22), Cursor = Cursors.Hand };
        var stripe = new Panel { Dock = DockStyle.Top, Height = 3, BackColor = accent };
        var numberLabel = new Label { Text = number, Font = new Font("Segoe UI Semibold", 10f), ForeColor = accent, AutoSize = true, Location = new Point(22, 24), Cursor = Cursors.Hand };
        titleLabel = new Label { Text = title, Font = new Font("Segoe UI Semibold", 16f), ForeColor = TextMain, AutoSize = true, Location = new Point(22, 58), Cursor = Cursors.Hand };
        detailLabel = new Label { Text = detail, Font = new Font("Segoe UI", 10f), ForeColor = TextMuted, Location = new Point(22, 100), AutoSize = false, Size = new Size(245, 142), Cursor = Cursors.Hand };
        var action = new Label { Text = "OPEN  →", Font = new Font("Segoe UI Semibold", 9.5f), ForeColor = accent, AutoSize = true, Anchor = AnchorStyles.Bottom | AnchorStyles.Left, Location = new Point(22, 272), Cursor = Cursors.Hand };
        card.Controls.Add(stripe);
        card.Controls.Add(numberLabel);
        card.Controls.Add(titleLabel);
        card.Controls.Add(detailLabel);
        card.Controls.Add(action);
        void HandleClick(object? _, EventArgs __) => click();
        card.Click += HandleClick;
        foreach (Control child in card.Controls) child.Click += HandleClick;
        card.Resize += (_, _) => action.Location = new Point(22, card.Height - 54);
        return card;
    }

    private void ShowManage()
    {
        if (!RequireInstallCheck()) return;
        var page = PageWithHeader("Manage Mods & Worlds", "Keep your PS5 game files organized.", out var content);
        _manageTabs.Dock = DockStyle.Fill;
        _manageTabs.Appearance = TabAppearance.Normal;
        _manageTabs.SizeMode = TabSizeMode.Normal;
        _manageTabs.TabPages.Clear();
        _manageTabs.TabPages.Add(BuildModsTab());
        _manageTabs.TabPages.Add(BuildWorldsTab());
        content.Controls.Add(_manageTabs);
        SetPage(page);
        _ = RefreshManagementAsync();
    }

    private TabPage BuildModsTab()
    {
        var tab = NewTab("Mods");
        var toolbar = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 58, BackColor = Surface, Padding = new Padding(12, 11, 8, 8), WrapContents = false };
        toolbar.Controls.Add(SmallLabel("Folder", 52));
        _modLocation.Width = 310;
        _modLocation.DropDownStyle = ComboBoxStyle.DropDownList;
        _modLocation.DisplayMember = nameof(ModChoice.Label);
        StyleCombo(_modLocation);
        _modLocation.SelectionChangeCommitted += async (_, _) => await RefreshModFilesAsync();
        toolbar.Controls.Add(_modLocation);
        var refresh = NewActionButton("Refresh", Blue, 90);
        var add = NewActionButton("Add mod…", Accent, 105);
        var remove = NewActionButton("Remove selected", Color.FromArgb(255, 145, 135), 150);
        _removeModButton = remove;
        refresh.Click += async (_, _) => await RefreshManagementAsync();
        add.Click += async (_, _) => await AddModAsync();
        remove.Click += async (_, _) => await RemoveModAsync();
        _modList.SelectedIndexChanged += (_, _) => UpdateModActions();
        toolbar.Controls.AddRange([refresh, add, remove]);
        _modList.Dock = DockStyle.Fill;
        _modList.View = View.Details;
        _modList.FullRowSelect = true;
        _modList.GridLines = false;
        _modList.Columns.Clear();
        _modList.Columns.Add("Mod file", 460);
        _modList.Columns.Add("Size", 120);
        _modList.Columns.Add("Status", 180);
        StyleList(_modList);
        var tip = new Label { Text = "JavaCraft, Controlify JavaCraft, and Black Shield Community mods are protected from removal.", Dock = DockStyle.Bottom, Height = 35, Padding = new Padding(12, 8, 0, 0), ForeColor = TextMuted, BackColor = Surface };
        tab.Controls.Add(_modList);
        tab.Controls.Add(toolbar);
        tab.Controls.Add(tip);
        return tab;
    }

    private TabPage BuildWorldsTab()
    {
        var tab = NewTab("Worlds");
        var toolbar = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 58, BackColor = Surface, Padding = new Padding(12, 11, 8, 8), WrapContents = false };
        var refresh = NewActionButton("Refresh", Blue, 95);
        var backup = NewActionButton("Back up selected…", Accent, 155);
        var remove = NewActionButton("Delete selected", Color.FromArgb(255, 145, 135), 145);
        refresh.Click += async (_, _) => await RefreshManagementAsync();
        backup.Click += async (_, _) => await BackupWorldAsync();
        remove.Click += async (_, _) => await DeleteWorldAsync();
        toolbar.Controls.AddRange([refresh, backup, remove]);
        _worldList.Dock = DockStyle.Fill;
        _worldList.View = View.Details;
        _worldList.FullRowSelect = true;
        _worldList.Columns.Clear();
        _worldList.Columns.Add("World", 410);
        _worldList.Columns.Add("Minecraft version", 190);
        _worldList.Columns.Add("Location", 300);
        StyleList(_worldList);
        var tip = new Label { Text = "Close Minecraft before backing up or deleting a world.", Dock = DockStyle.Bottom, Height = 35, Padding = new Padding(12, 8, 0, 0), ForeColor = TextMuted, BackColor = Surface };
        tab.Controls.Add(_worldList);
        tab.Controls.Add(toolbar);
        tab.Controls.Add(tip);
        return tab;
    }

    private void ShowInstall()
    {
        if (!RequireInstallCheck()) return;
        var actionName = _installState is InstallState.Installed or InstallState.RepairRequired ? "Repair JavaCraft" : "Install JavaCraft";
        var page = PageWithHeader(actionName, "Upload or restore the complete local JavaCraft PS5 package.", out var content);
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 5, Padding = new Padding(4, 14, 4, 0), BackColor = Canvas };
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 62));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 56));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 18));
        var pathRow = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false, BackColor = Canvas, Padding = new Padding(0, 5, 0, 5) };
        pathRow.Controls.Add(SmallLabel("Package folder", 110));
        _packagePath.Width = 630;
        StyleTextBox(_packagePath);
        pathRow.Controls.Add(_packagePath);
        var browse = NewActionButton("Browse…", Blue, 100);
        browse.Click += (_, _) => BrowsePackage();
        pathRow.Controls.Add(browse);
        layout.Controls.Add(pathRow, 0, 0);
        var info = new Label { Text = "Select the folder named “data” that contains “homebrew” and “prospero-win”. You can use dist\\PS5\\data from the JavaCraft project.", Dock = DockStyle.Fill, ForeColor = TextMuted, TextAlign = ContentAlignment.MiddleLeft };
        layout.Controls.Add(info, 0, 1);
        var actionRow = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false, BackColor = Canvas, Padding = new Padding(0, 7, 0, 0) };
        var install = NewActionButton(actionName, Accent, 170);
        install.Height = 40;
        install.Click += async (_, _) => await InstallAsync();
        actionRow.Controls.Add(install);
        actionRow.Controls.Add(new Label { Text = "Replaces app files; saved worlds and player settings stay on the console.", AutoSize = true, ForeColor = TextMuted, Padding = new Padding(12, 10, 0, 0) });
        layout.Controls.Add(actionRow, 0, 2);
        ConfigureLog(_operationLog);
        layout.Controls.Add(_operationLog, 0, 3);
        layout.Controls.Add(new Label { Text = "FTP is unencrypted. Connect only to your PS5 on a trusted LAN.", ForeColor = Color.FromArgb(236, 191, 105), Dock = DockStyle.Fill, Font = new Font("Segoe UI", 9f) }, 0, 4);
        content.Controls.Add(layout);
        AutoFindPackage();
        SetPage(page);
    }

    private void ShowUninstall()
    {
        if (!RequireInstallCheck()) return;
        var page = PageWithHeader("Uninstall from PS5", "Remove JavaCraft and its Minecraft-only files from this console.", out var content);
        var panel = new Panel { Dock = DockStyle.Top, Height = 265, BackColor = Surface, Padding = new Padding(24) };
        panel.Controls.Add(new Label { Text = "Remove JavaCraft PS5", Font = new Font("Segoe UI Semibold", 17f), ForeColor = TextMain, AutoSize = true, Location = new Point(24, 20) });
        panel.Controls.Add(new Label { Text = "Permanently deletes the JavaCraft app and its bundled emulator, the Minecraft prefix (Java runtime, assets, libraries, mods, settings, account data, and every world), its profile, and its OnionHEN / etaHEN allowlist entries. Shared Prospero Win files and other apps’ profiles are preserved.", Font = new Font("Segoe UI", 10.5f), ForeColor = TextMuted, AutoSize = false, Size = new Size(780, 88), Location = new Point(26, 62) });
        var removeButton = NewActionButton("Uninstall JavaCraft", Color.FromArgb(255, 145, 135), 190);
        removeButton.Height = 42;
        removeButton.Location = new Point(24, 175);
        removeButton.Click += async (_, _) => await UninstallAsync();
        panel.Controls.Add(removeButton);
        _operationLog.Clear();
        ConfigureLog(_operationLog);
        _operationLog.Dock = DockStyle.Fill;
        content.Controls.Add(_operationLog);
        content.Controls.Add(panel);
        SetPage(page);
    }

    private Control PageWithHeader(string title, string subtitle, out Panel content)
    {
        var page = new Panel { Dock = DockStyle.Fill, BackColor = Canvas };
        var header = new Panel { Dock = DockStyle.Top, Height = 100, BackColor = Canvas };
        var back = NewActionButton("←  Home", Panel2, 112);
        back.Location = new Point(0, 2);
        back.ForeColor = TextMain;
        back.Click += (_, _) => ShowHome();
        header.Controls.Add(back);
        header.Controls.Add(new Label { Text = title, Font = new Font("Segoe UI Semibold", 21f), ForeColor = TextMain, AutoSize = true, Location = new Point(136, 0) });
        header.Controls.Add(new Label { Text = subtitle, Font = new Font("Segoe UI", 10f), ForeColor = TextMuted, AutoSize = true, Location = new Point(139, 42) });
        content = new Panel { Dock = DockStyle.Fill, BackColor = Canvas };
        page.Controls.Add(content);
        page.Controls.Add(header);
        return page;
    }

    private void SetPage(Control page)
    {
        _body.Controls.Clear();
        page.Dock = DockStyle.Fill;
        _body.Controls.Add(page);
    }

    private async Task ConnectAsync()
    {
        if (!TryGetTarget(out var host, out var port)) return;
        SetBusy(true, "Connecting and checking JavaCraft…");
        try
        {
            var state = await Task.Run(() =>
            {
                using var ftp = new Ps5FtpClient(host, port);
                ftp.TestConnection();
                return DetectInstallState(ftp);
            });
            _installState = state;
            _connectionStatus.Text = "Connected";
            _connectionStatus.ForeColor = Accent;
            _footerStatus.Text = $"Connected · {InstallStateText(state)}";
            UpdateHomeInstallOverview();
            SaveSettings();
        }
        catch (Exception ex)
        {
            _installState = InstallState.Unknown;
            _connectionStatus.Text = "Offline";
            _connectionStatus.ForeColor = Color.FromArgb(255, 145, 135);
            UpdateHomeInstallOverview();
            ShowError("Could not connect to the PS5 FTP service.", ex);
        }
        finally { SetBusy(false); }
    }

    private static InstallState DetectInstallState(Ps5FtpClient ftp)
    {
        var requiredFiles = new[]
        {
            AppPath + "/eboot.bin",
            ProfilesPath.Replace("profiles.lst", ProfileFile, StringComparison.Ordinal),
            PrefixPath + "/system.reg",
            PrefixPath + "/user.reg",
            PrefixPath + "/drive_c/windows/system32/kernel32.dll",
            PrefixPath + "/drive_c/JavaCraftPS5/runtime/java-runtime-epsilon/bin/javaw.exe",
            PrefixPath + "/drive_c/JavaCraftPS5/game/mods/Black Shield Community 1.0.0.jar"
        };
        var present = requiredFiles.Count(path => RemoteFileExists(ftp, path));
        if (present == 0) return InstallState.NotInstalled;
        return present == requiredFiles.Length ? InstallState.Installed : InstallState.RepairRequired;
    }

    private static bool RemoteFileExists(Ps5FtpClient ftp, string path)
    {
        try { return ftp.GetSize(path) is not null; }
        catch (IOException ex) when (ex.Message.Contains("550", StringComparison.Ordinal)) { return false; }
    }

    private static string InstallStateText(InstallState state) => state switch
    {
        InstallState.NotInstalled => "not installed",
        InstallState.Installed => "installed",
        InstallState.RepairRequired => "incomplete · repair available",
        _ => "not checked"
    };

    private void UpdateHomeInstallOverview()
    {
        if (_homeWorkflowStatus is not null && !_homeWorkflowStatus.IsDisposed)
        {
            _homeWorkflowStatus.Text = _installState switch
            {
                InstallState.NotInstalled => "Step 2: not installed. Select Install JavaCraft.",
                InstallState.Installed => "Step 2: JavaCraft is installed. Repair is available if needed.",
                InstallState.RepairRequired => "Step 2: files are incomplete. Select Repair JavaCraft.",
                _ => "Step 2: connect to scan the installation status."
            };
            _homeWorkflowStatus.ForeColor = _installState == InstallState.RepairRequired
                ? Color.FromArgb(255, 191, 105) : Blue;
        }

        if (_homeInstallTitle is null || _homeInstallDetail is null
            || _homeInstallTitle.IsDisposed || _homeInstallDetail.IsDisposed) return;
        switch (_installState)
        {
            case InstallState.NotInstalled:
                _homeInstallTitle.Text = "Install JavaCraft";
                _homeInstallDetail.Text = "The PS5 is ready. Upload the complete local package to install JavaCraft.";
                break;
            case InstallState.Installed:
                _homeInstallTitle.Text = "Repair JavaCraft";
                _homeInstallDetail.Text = "JavaCraft is installed. Restore its files if the app has a launch problem.";
                break;
            case InstallState.RepairRequired:
                _homeInstallTitle.Text = "Repair JavaCraft";
                _homeInstallDetail.Text = "Some required files are missing. Upload the package to repair the installation.";
                break;
            default:
                _homeInstallTitle.Text = "Install JavaCraft";
                _homeInstallDetail.Text = "Connect to the PS5 first, then install or repair from the local package.";
                break;
        }
    }

    private void InvalidateInstallCheck()
    {
        if (_installState == InstallState.Unknown) return;
        _installState = InstallState.Unknown;
        _connectionStatus.Text = "Not checked";
        _connectionStatus.ForeColor = TextMuted;
        UpdateHomeInstallOverview();
    }

    private bool RequireInstallCheck()
    {
        if (_installState != InstallState.Unknown) return true;
        MessageBox.Show(this, "Enter the PS5 IP and select Connect first.", "Connect to PS5", MessageBoxButtons.OK, MessageBoxIcon.Information);
        return false;
    }

    private void UpdateModActions()
    {
        if (_removeModButton is null || _removeModButton.IsDisposed) return;
        var removable = _modList.SelectedItems.Count > 0
            && _modList.SelectedItems[0].Tag is FtpEntry entry
            && !IsProtectedMod(entry.Name);
        _removeModButton.Enabled = !_busy && removable;
    }

    private async Task RefreshManagementAsync()
    {
        if (_busy || !TryGetTarget(out var host, out var port)) return;
        SetBusy(true, "Reading PS5 files…");
        try
        {
            var result = await Task.Run(() =>
            {
                using var ftp = new Ps5FtpClient(host, port);
                var choices = new List<ModChoice> { new("Main mods folder", GamePath + "/mods") };
                try
                {
                    foreach (var instance in ftp.List(GamePath + "/instances").Where(x => x.IsDirectory))
                        choices.Add(new ModChoice(instance.Name + " · instance mods", GamePath + "/instances/" + instance.Name + "/mods"));
                }
                catch { }
                var worlds = new List<WorldChoice>();
                AddWorlds(ftp, GamePath + "/saves", "Default", worlds);
                foreach (var instance in choices.Skip(1))
                {
                    var name = instance.Label[..instance.Label.IndexOf(" ·", StringComparison.Ordinal)];
                    AddWorlds(ftp, GamePath + "/instances/" + name + "/saves", name, worlds);
                }
                return (choices, worlds);
            });

            var selectedPath = CurrentModPathSafe();
            _modLocation.BeginUpdate();
            _modLocation.Items.Clear();
            foreach (var choice in result.choices) _modLocation.Items.Add(choice);
            var selected = result.choices.FindIndex(x => x.Path == selectedPath);
            _modLocation.SelectedIndex = selected >= 0 ? selected : 0;
            _modLocation.EndUpdate();
            if (_modLocation.SelectedItem is ModChoice selectedChoice)
            {
                var selectedMods = await Task.Run(() =>
                {
                    using var ftp = new Ps5FtpClient(host, port);
                    return ftp.List(selectedChoice.Path).Where(x => !x.IsDirectory && x.Name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)).ToList();
                });
                _modList.Items.Clear();
                foreach (var mod in selectedMods)
                {
                    var item = new ListViewItem(mod.Name);
                    item.SubItems.Add(FormatSize(mod.Size));
                    var protectedFile = IsProtectedMod(mod.Name);
                    item.SubItems.Add(protectedFile ? "Protected" : "User mod");
                    item.ForeColor = protectedFile ? Accent : TextMain;
                    item.Tag = mod;
                    _modList.Items.Add(item);
                }
                UpdateModActions();
            }
            _worldList.Items.Clear();
            foreach (var world in result.worlds.OrderBy(w => w.Version).ThenBy(w => w.Name))
            {
                var item = new ListViewItem(world.Name);
                item.SubItems.Add(world.Version);
                item.SubItems.Add(world.Path.Replace(GamePath, "…", StringComparison.Ordinal));
                item.Tag = world;
                _worldList.Items.Add(item);
            }
            _connectionStatus.Text = "Connected";
            _connectionStatus.ForeColor = Accent;
            _footerStatus.Text = $"Found {_modList.Items.Count} mods · {result.worlds.Count} worlds";
        }
        catch (Exception ex) { ShowError("Could not read the JavaCraft folders on the PS5.", ex); }
        finally { SetBusy(false); }

        string CurrentModPathSafe() => _modLocation.SelectedItem is ModChoice current ? current.Path : GamePath + "/mods";
    }

    private async Task RefreshModFilesAsync()
    {
        if (_busy || _modLocation.SelectedItem is not ModChoice choice || !TryGetTarget(out var host, out var port)) return;
        SetBusy(true, "Reading mods…");
        try
        {
            var entries = await Task.Run(() =>
            {
                using var ftp = new Ps5FtpClient(host, port);
                return ftp.List(choice.Path).Where(x => !x.IsDirectory && x.Name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)).ToList();
            });
            _modList.Items.Clear();
            foreach (var mod in entries)
            {
                var item = new ListViewItem(mod.Name);
                item.SubItems.Add(FormatSize(mod.Size));
                var protectedFile = IsProtectedMod(mod.Name);
                item.SubItems.Add(protectedFile ? "Protected" : "User mod");
                item.ForeColor = protectedFile ? Accent : TextMain;
                item.Tag = mod;
                _modList.Items.Add(item);
            }
            UpdateModActions();
        }
        catch (Exception ex) { ShowError("Could not read mods in this folder.", ex); }
        finally { SetBusy(false); }
    }

    private async Task AddModAsync()
    {
        if (_modLocation.SelectedItem is not ModChoice choice) return;
        using var dialog = new OpenFileDialog { Title = "Select a Fabric mod", Filter = "Fabric mod (*.jar)|*.jar", Multiselect = false };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        if (IsProtectedModArchive(dialog.FileName))
        {
            MessageBox.Show(this, "This filename is reserved for a protected JavaCraft or Black Shield mod.", "Protected file", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        if (!TryGetTarget(out var host, out var port)) return;
        SetBusy(true, "Uploading mod…");
        try
        {
            var local = new FileInfo(dialog.FileName);
            var remote = choice.Path + "/" + local.Name;
            await Task.Run(() =>
            {
                using var ftp = new Ps5FtpClient(host, port);
                if (ftp.GetSize(remote) is not null) throw new IOException("A file with this name already exists. Remove it first if you want to replace it.");
                UploadAtomically(ftp, local.FullName, remote, local.Length);
                if (ftp.GetSize(remote) != local.Length) throw new IOException("The uploaded mod could not be verified.");
            });
            LogLine(_operationLog, "Added " + local.Name);
            SetBusy(false);
            await RefreshManagementAsync();
        }
        catch (Exception ex) { ShowError("The mod could not be uploaded.", ex); }
        finally { SetBusy(false); }
    }

    private async Task RemoveModAsync()
    {
        if (_modList.SelectedItems.Count == 0 || _modLocation.SelectedItem is not ModChoice choice) return;
        var selected = _modList.SelectedItems[0];
        var entry = selected.Tag as FtpEntry;
        if (entry is null) return;
        if (IsProtectedMod(entry.Name))
        {
            MessageBox.Show(this, "Core JavaCraft and Black Shield mods cannot be removed here.", "Protected file", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (MessageBox.Show(this, $"Remove user mod “{entry.Name}” from the PS5?", "Remove mod", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        if (!TryGetTarget(out var host, out var port)) return;
        SetBusy(true, "Removing mod…");
        try
        {
            await Task.Run(() => { using var ftp = new Ps5FtpClient(host, port); ftp.DeleteFile(choice.Path + "/" + entry.Name); });
            SetBusy(false);
            await RefreshManagementAsync();
        }
        catch (Exception ex) { ShowError("The mod could not be removed.", ex); }
        finally { SetBusy(false); }
    }

    private async Task BackupWorldAsync()
    {
        if (_worldList.SelectedItems.Count == 0) return;
        var world = _worldList.SelectedItems[0].Tag as WorldChoice;
        if (world is null || !TryGetTarget(out var host, out var port)) return;
        using var dialog = new SaveFileDialog { Title = "Back up Minecraft world", FileName = world.Name + ".zip", Filter = "ZIP archive (*.zip)|*.zip", OverwritePrompt = true };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        SetBusy(true, "Backing up world…");
        try
        {
            await Task.Run(() =>
            {
                using var ftp = new Ps5FtpClient(host, port);
                using var file = File.Create(dialog.FileName);
                using var archive = new ZipArchive(file, ZipArchiveMode.Create);
                DownloadWorld(ftp, world.Path, archive, world.Name, count => BeginInvoke(() => _progressText.Text = $"Downloaded {FormatSize(count)}"));
            });
            _footerStatus.Text = "World backup saved: " + dialog.FileName;
        }
        catch (Exception ex) { ShowError("The world backup failed.", ex); }
        finally { SetBusy(false); }
    }

    private async Task DeleteWorldAsync()
    {
        if (_worldList.SelectedItems.Count == 0) return;
        var world = _worldList.SelectedItems[0].Tag as WorldChoice;
        if (world is null) return;
        if (MessageBox.Show(this, $"Permanently delete world “{world.Name}” from version {world.Version}? This cannot be undone.", "Delete world", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        if (!TryGetTarget(out var host, out var port)) return;
        SetBusy(true, "Deleting world…");
        try
        {
            await Task.Run(() =>
            {
                using var ftp = new Ps5FtpClient(host, port);
                EnsureChildOfGameSaves(world.Path);
                DeleteTree(ftp, world.Path);
            });
            SetBusy(false);
            await RefreshManagementAsync();
        }
        catch (Exception ex) { ShowError("The world could not be deleted.", ex); }
        finally { SetBusy(false); }
    }

    private async Task InstallAsync()
    {
        if (!RequireInstallCheck()) return;
        var dataRoot = _packagePath.Text.Trim().Trim('"');
        if (!TryGetTarget(out var host, out var port)) return;
        InstallPlan plan;
        try
        {
            SetBusy(true, "Preparing package…");
            plan = await Task.Run(() =>
            {
                if (!TryValidatePackage(dataRoot, out var app, out var library, out var error))
                    return new InstallPlan(null, null, [], error);
                return new InstallPlan(app, library, CollectInstallFiles(app!, library!), null);
            });
        }
        catch (Exception ex)
        {
            ShowError("Could not prepare the JavaCraft package.", ex);
            return;
        }
        finally { SetBusy(false); }

        if (!plan.IsValid)
        {
            MessageBox.Show(this, plan.Error ?? "Select the complete JavaCraft package.", "JavaCraft package not found", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        var files = plan.Files;
        var total = files.Sum(x => x.Length);
        var actionName = _installState is InstallState.Installed or InstallState.RepairRequired ? "Repair" : "Install";
        if (MessageBox.Show(this, $"{actionName} JavaCraft using {files.Count:N0} files ({FormatSize(total)})?\n\nSaved worlds, account data, and player settings will stay on the PS5. Close Minecraft first.", actionName + " JavaCraft", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;

        SetBusy(true, actionName + "ing JavaCraft…");
        _operationLog.Clear();
        try
        {
            IProgress<ProgressInfo> progress = new Progress<ProgressInfo>(info =>
            {
                if (info.Percent >= 0)
                {
                    _progressBar.Style = ProgressBarStyle.Continuous;
                    _progressBar.Value = Math.Clamp(info.Percent, 0, 100);
                }
                _progressText.Text = info.Message;
            });
            await Task.Run(() =>
            {
                using var ftp = new Ps5FtpClient(host, port);
                ftp.TestConnection();
                DisableSelfConversion(ftp);
                long sent = 0;
                var index = 0;
                progress.Report(new ProgressInfo($"0/{files.Count:N0} files · preparing upload", 0));
                foreach (var file in files)
                {
                    index++;
                    long currentFile = 0;
                    UploadAtomically(ftp, file.Source, file.Remote, file.Length, count =>
                    {
                        sent += count;
                        currentFile += count;
                        if (currentFile % (4 * 1024 * 1024) < count)
                            progress.Report(new ProgressInfo($"{index:N0}/{files.Count:N0} files · {FormatSize(sent)} of {FormatSize(total)}", total == 0 ? 100 : (int)(sent * 100 / total)));
                    });
                    if (index % 25 == 0 || index == files.Count)
                        progress.Report(new ProgressInfo($"{index:N0}/{files.Count:N0} files · {FormatSize(sent)} of {FormatSize(total)}", total == 0 ? 100 : (int)(sent * 100 / total)));
                }
                EnsureProfileListing(ftp);
                EnsureCryptbase(ftp, plan.AppRoot!);
                EnsureWineDirectories(ftp);
                UpdateOnionHenAllowlist(ftp);
                UpdateEtaHenAllowlist(ftp);
                foreach (var executable in files.Where(x => x.Remote.StartsWith(AppPath + "/", StringComparison.Ordinal)
                    && (Path.GetFileName(x.Remote).Equals("eboot.bin", StringComparison.OrdinalIgnoreCase)
                        || Path.GetExtension(x.Remote).Equals(".prx", StringComparison.OrdinalIgnoreCase))))
                    TryChmod(ftp, executable.Remote);
                progress.Report(new ProgressInfo("Install complete", 100));
            });
            _installState = InstallState.Installed;
            UpdateHomeInstallOverview();
            LogLine(_operationLog, $"JavaCraft {actionName.ToLowerInvariant()} completed on {host}. Saved worlds and player settings were preserved.");
            _footerStatus.Text = $"JavaCraft {actionName.ToLowerInvariant()} completed";
            _connectionStatus.Text = "Connected";
            _connectionStatus.ForeColor = Accent;
        }
        catch (Exception ex) { ShowError("JavaCraft installation stopped. Check the connection and package files, then try again.", ex); }
        finally { SetBusy(false); }
    }

    private async Task UninstallAsync()
    {
        if (!RequireInstallCheck()) return;
        if (!TryGetTarget(out var host, out var port)) return;
        const string detail = "This permanently deletes the JavaCraft app and its bundled emulator, the full Minecraft prefix (including all versions, Java runtime, assets, libraries, mods, settings, account data, and worlds), the JavaCraft profile, and its OnionHEN / etaHEN allowlist entries. Other Prospero Win app profiles are kept.";
        if (MessageBox.Show(this, detail + "\n\nContinue?", "Confirm full uninstall", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        if (MessageBox.Show(this, "Final confirmation: permanently delete all JavaCraft and Minecraft files, including every saved world?", "Delete all JavaCraft data", MessageBoxButtons.YesNo, MessageBoxIcon.Stop) != DialogResult.Yes) return;

        SetBusy(true, "Uninstalling…");
        _operationLog.Clear();
        try
        {
            await Task.Run(() =>
            {
                using var ftp = new Ps5FtpClient(host, port);
                ftp.TestConnection();
                DeleteTreeIfPresent(ftp, AppPath);
                DeleteTreeIfPresent(ftp, PrefixPath);
                DeleteJavaCraftProfileFiles(ftp);
                RemoveProfileListing(ftp);
                RemoveOnionHenAllowlist(ftp);
                RemoveEtaHenAllowlist(ftp);
            });
            LogLine(_operationLog, "JavaCraft app, bundled emulator, Minecraft prefix and worlds, profile, and allowlist entry removed. Other apps' shared Prospero Win profiles were preserved.");
            _installState = InstallState.NotInstalled;
            UpdateHomeInstallOverview();
            _connectionStatus.Text = "Connected";
            _connectionStatus.ForeColor = Accent;
            _footerStatus.Text = "JavaCraft and all Minecraft data removed";
        }
        catch (Exception ex) { ShowError("Uninstall stopped before completion.", ex); }
        finally { SetBusy(false); }
    }

    private sealed record UploadFile(string Source, string Remote, long Length);

    private static List<UploadFile> CollectInstallFiles(string appRoot, string libraryRoot)
    {
        var list = new List<UploadFile>();
        AddTree(appRoot, AppPath, list, preserveUserData: false);
        AddTree(libraryRoot, LibraryPath, list, preserveUserData: true);
        return list;
    }

    private static void AddTree(string root, string remoteRoot, List<UploadFile> output, bool preserveUserData)
    {
        foreach (var file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
        {
            var info = new FileInfo(file);
            if ((info.Attributes & FileAttributes.ReparsePoint) != 0) continue;
            var relative = Path.GetRelativePath(root, file).Replace('\\', '/');
            var remote = remoteRoot + "/" + relative;
            if (preserveUserData && IsMutablePlayerData(remote)) continue;
            output.Add(new UploadFile(file, remote, info.Length));
        }
    }

    private static bool IsMutablePlayerData(string remotePath)
    {
        var path = remotePath.Replace('\\', '/');
        return path.Contains("/game/saves/", StringComparison.OrdinalIgnoreCase)
            || path.Contains("/game/instances/", StringComparison.OrdinalIgnoreCase)
                && path.Contains("/saves/", StringComparison.OrdinalIgnoreCase)
            || path.Contains("/screenshots/", StringComparison.OrdinalIgnoreCase)
            || path.Contains("/crash-reports/", StringComparison.OrdinalIgnoreCase)
            || path.Contains("/logs/", StringComparison.OrdinalIgnoreCase)
            || Path.GetFileName(path).Equals("options.txt", StringComparison.OrdinalIgnoreCase)
            || Path.GetFileName(path).Equals("launcher-settings.json", StringComparison.OrdinalIgnoreCase)
            || Path.GetFileName(path).Equals(".javacraft-mod-updates.json", StringComparison.OrdinalIgnoreCase);
    }

    private static void UploadAtomically(Ps5FtpClient ftp, string sourcePath, string remotePath, long length, Action<long>? onBytes = null)
    {
        var temporary = remotePath + ".javacraft-uploading";
        var backup = remotePath + ".javacraft-backup-" + Guid.NewGuid().ToString("N")[..8];
        try
        {
            using (var input = File.OpenRead(sourcePath)) ftp.Store(temporary, input, length, onBytes);
            if (ftp.GetSize(temporary) != length) throw new IOException("The PS5 did not verify " + Path.GetFileName(sourcePath));
            var hadPrevious = ftp.GetSize(remotePath) is not null;
            if (hadPrevious) ftp.Rename(remotePath, backup);
            try { ftp.Rename(temporary, remotePath); }
            catch
            {
                if (hadPrevious) ftp.Rename(backup, remotePath);
                throw;
            }
            if (hadPrevious)
            {
                try { ftp.DeleteFile(backup); } catch { }
            }
        }
        catch
        {
            try { if (ftp.GetSize(temporary) is not null) ftp.DeleteFile(temporary); } catch { }
            throw;
        }
    }

    private static void EnsureProfileListing(Ps5FtpClient ftp)
    {
        var text = ReadTextIfPresent(ftp, ProfilesPath);
        var names = text.Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries)
            .Select(x => x.Trim()).Where(x => x.Length > 0).ToList();
        if (!names.Contains(ProfileFile, StringComparer.OrdinalIgnoreCase)) names.Add(ProfileFile);
        WriteTextWithBackup(ftp, ProfilesPath, string.Join("\n", names) + "\n");
    }

    private static void RemoveProfileListing(Ps5FtpClient ftp)
    {
        if (ftp.GetSize(ProfilesPath) is not null)
        {
            var text = ftp.RetrieveText(ProfilesPath);
            var names = text.Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries)
                .Select(x => x.Trim()).Where(x => x.Length > 0 && !x.Equals(ProfileFile, StringComparison.OrdinalIgnoreCase));
            WriteTextWithBackup(ftp, ProfilesPath, string.Join("\n", names) + "\n");
        }
        DeleteFileIfPresent(ftp, ProfilesPath + ".javacraft-backup");
        DeleteFileIfPresent(ftp, ProfilesPath + ".javacraft-tmp");
        DeleteFileIfPresent(ftp, ProfilesPath + ".javacraft-uploading");
    }

    private static void DeleteJavaCraftProfileFiles(Ps5FtpClient ftp)
    {
        var directory = LibraryPath + "/profiles";
        IReadOnlyList<FtpEntry> entries;
        try { entries = ftp.List(directory); }
        catch (IOException ex) when (ex.Message.Contains("550", StringComparison.Ordinal)) { return; }
        foreach (var entry in entries.Where(x => !x.IsDirectory
            && (x.Name.Equals(ProfileFile, StringComparison.OrdinalIgnoreCase)
                || x.Name.StartsWith(ProfileFile + ".javacraft-", StringComparison.OrdinalIgnoreCase))))
            DeleteFileIfPresent(ftp, directory + "/" + entry.Name);
    }

    private static void EnsureCryptbase(Ps5FtpClient ftp, string appRoot)
    {
        var source = Path.Combine(appRoot, "win", "wine", "lib", "wine", "x86_64-windows", "cryptbase.dll");
        if (!File.Exists(source)) return;
        const string target = PrefixPath + "/drive_c/windows/system32/cryptbase.dll";
        var size = new FileInfo(source).Length;
        using (var input = File.OpenRead(source)) ftp.Store(target + ".javacraft-uploading", input, size);
        if (ftp.GetSize(target + ".javacraft-uploading") != size) throw new IOException("The PS5 cryptbase.dll upload did not verify.");
        var backup = target + ".javacraft-backup-" + Guid.NewGuid().ToString("N")[..8];
        var hadPrevious = ftp.GetSize(target) is not null;
        if (hadPrevious) ftp.Rename(target, backup);
        try { ftp.Rename(target + ".javacraft-uploading", target); }
        catch
        {
            if (hadPrevious) ftp.Rename(backup, target);
            throw;
        }
        if (hadPrevious) { try { ftp.DeleteFile(backup); } catch { } }
    }

    private static void EnsureWineDirectories(Ps5FtpClient ftp)
    {
        foreach (var relative in new[]
        {
            "drive_c/windows/temp",
            "drive_c/users/prospero/AppData/Local/Temp",
            "drive_c/users/prospero/AppData/LocalLow",
            "drive_c/users/prospero/AppData/Roaming",
            "drive_c/users/prospero/Desktop",
            "drive_c/users/prospero/Documents",
            "drive_c/users/prospero/Saved Games",
            "dosdevices"
        })
            ftp.EnsureDirectory(PrefixPath + "/" + relative);
    }

    private static void UpdateOnionHenAllowlist(Ps5FtpClient ftp)
    {
        const string path = "/data/OnionHEN/config.ini";
        string current;
        try { current = ftp.RetrieveText(path); }
        catch { return; }
        var updated = UpdateAllowlist(current, enableAppJailbreak: true);
        if (updated != current) WriteTextWithBackup(ftp, path, updated);
    }

    private static void UpdateEtaHenAllowlist(Ps5FtpClient ftp)
    {
        const string path = "/data/etaHEN/config.ini";
        try
        {
            if (ftp.GetSize(path) is null) return;
            var current = ftp.RetrieveText(path);
            var updated = UpdateAllowlist(current, enableAppJailbreak: false);
            if (updated != current) WriteTextWithBackup(ftp, path, updated);
        }
        catch (IOException ex) when (ex.Message.Contains("550", StringComparison.Ordinal)) { }
    }

    private static void RemoveOnionHenAllowlist(Ps5FtpClient ftp)
    {
        const string path = "/data/OnionHEN/config.ini";
        try
        {
            if (ftp.GetSize(path) is not null)
            {
                var current = ftp.RetrieveText(path);
                var updated = RemoveAllowlist(current);
                if (updated != current) WriteTextWithBackup(ftp, path, updated);
            }
        }
        catch (IOException ex) when (ex.Message.Contains("550", StringComparison.Ordinal)) { }
        DeleteFileIfPresent(ftp, path + ".javacraft-backup");
        DeleteFileIfPresent(ftp, path + ".javacraft-tmp");
    }

    private static void RemoveEtaHenAllowlist(Ps5FtpClient ftp)
    {
        const string path = "/data/etaHEN/config.ini";
        try
        {
            if (ftp.GetSize(path) is not null)
            {
                var current = ftp.RetrieveText(path);
                var updated = RemoveAllowlist(current);
                if (updated != current) WriteTextWithBackup(ftp, path, updated);
            }
        }
        catch (IOException ex) when (ex.Message.Contains("550", StringComparison.Ordinal)) { }
        DeleteFileIfPresent(ftp, path + ".javacraft-backup");
        DeleteFileIfPresent(ftp, path + ".javacraft-tmp");
    }

    private static string RemoveAllowlist(string text)
    {
        var lines = text.TrimStart('\uFEFF').Replace("\r\n", "\n").Split('\n').ToList();
        var start = lines.FindIndex(x => x.Trim().Equals("[app_jailbreak]", StringComparison.OrdinalIgnoreCase));
        if (start < 0) return text;
        var end = lines.FindIndex(start + 1, x => x.TrimStart().StartsWith('[') && x.TrimEnd().EndsWith(']'));
        if (end < 0) end = lines.Count;
        var changed = false;
        for (var i = start + 1; i < end; i++)
        {
            var split = lines[i].Split('=', 2);
            if (split.Length != 2 || !split[0].Trim().Equals("exact_title_ids", StringComparison.OrdinalIgnoreCase)) continue;
            var oldIds = split[1].Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToList();
            if (!oldIds.Any(x => x.Equals(TitleId, StringComparison.OrdinalIgnoreCase))) continue;
            var ids = oldIds.Select(x => x.ToUpperInvariant())
                .Where(x => !x.Equals("NONE", StringComparison.OrdinalIgnoreCase)
                    && !x.Equals(TitleId, StringComparison.OrdinalIgnoreCase)).Distinct().ToList();
            lines[i] = "exact_title_ids=" + (ids.Count == 0 ? "NONE" : string.Join(',', ids));
            changed = true;
        }
        return changed ? string.Join("\n", lines).TrimEnd('\n') + "\n" : text;
    }

    private static string UpdateAllowlist(string text, bool enableAppJailbreak)
    {
        var lines = text.TrimStart('\uFEFF').Replace("\r\n", "\n").Split('\n').ToList();
        var start = lines.FindIndex(x => x.Trim().Equals("[app_jailbreak]", StringComparison.OrdinalIgnoreCase));
        if (start < 0)
        {
            if (lines.Count > 0 && !string.IsNullOrWhiteSpace(lines[^1])) lines.Add("");
            lines.Add("[app_jailbreak]");
            if (enableAppJailbreak) lines.Add("enabled=true");
            lines.Add("exact_title_ids=" + TitleId);
            return string.Join("\n", lines).TrimEnd('\n') + "\n";
        }
        var end = lines.FindIndex(start + 1, x => x.TrimStart().StartsWith('[') && x.TrimEnd().EndsWith(']'));
        if (end < 0) end = lines.Count;
        var enabledIndex = -1;
        var idsIndex = -1;
        var ids = new List<string>();
        for (var i = start + 1; i < end; i++)
        {
            var split = lines[i].Split('=', 2);
            if (split.Length != 2) continue;
            if (split[0].Trim().Equals("enabled", StringComparison.OrdinalIgnoreCase)) enabledIndex = i;
            if (split[0].Trim().Equals("exact_title_ids", StringComparison.OrdinalIgnoreCase))
            {
                idsIndex = i;
                ids = split[1].Split(',').Select(x => x.Trim().ToUpperInvariant())
                    .Where(x => x.Length > 0 && x != "NONE").Distinct().ToList();
            }
        }
        if (!ids.Contains(TitleId, StringComparer.OrdinalIgnoreCase)) ids.Add(TitleId);
        if (ids.Count > 20) throw new IOException("The HEN's exact app allowlist is full; JavaCraft was not added.");
        if (enableAppJailbreak)
        {
            if (enabledIndex >= 0) lines[enabledIndex] = "enabled=true";
            else { lines.Insert(start + 1, "enabled=true"); if (idsIndex >= 0) idsIndex++; end++; }
        }
        if (idsIndex >= 0) lines[idsIndex] = "exact_title_ids=" + string.Join(',', ids);
        else lines.Insert(end, "exact_title_ids=" + string.Join(',', ids));
        return string.Join("\n", lines).TrimEnd('\n') + "\n";
    }

    private static void WriteTextWithBackup(Ps5FtpClient ftp, string path, string text)
    {
        var backup = path + ".javacraft-backup";
        var existing = ReadTextIfPresent(ftp, path);
        var hasTarget = ftp.GetSize(path) is not null;
        if (ftp.GetSize(backup) is null && hasTarget) ftp.StoreText(backup, existing);
        var temp = path + ".javacraft-tmp";
        ftp.StoreText(temp, text);
        if (ftp.GetSize(temp) is null) throw new IOException("Could not verify " + path);
        if (hasTarget) ftp.DeleteFile(path);
        ftp.Rename(temp, path);
    }

    private static string ReadTextIfPresent(Ps5FtpClient ftp, string path)
    {
        try { return ftp.RetrieveText(path); }
        catch { return ""; }
    }

    private static void TryChmod(Ps5FtpClient ftp, string path)
    {
        var (parent, name) = Ps5FtpClient.SplitPath(path);
        try
        {
            ftp.EnsureDirectory(parent);
            // SITE CHMOD is optional on PS5 FTP implementations.
            ftp.TryCommand("SITE CHMOD 755 " + name);
        }
        catch { }
    }

    private static void DisableSelfConversion(Ps5FtpClient ftp)
    {
        for (var attempt = 0; attempt < 2; attempt++)
        {
            var response = ftp.TryCommand("SELF");
            if (response is null || response.Contains("disabled", StringComparison.OrdinalIgnoreCase)) return;
        }
    }

    private static void DeleteTree(Ps5FtpClient ftp, string path)
    {
        var entries = ftp.List(path);
        foreach (var entry in entries)
        {
            var child = path.TrimEnd('/') + "/" + entry.Name;
            if (entry.IsDirectory) DeleteTree(ftp, child);
            else ftp.DeleteFile(child);
        }
        ftp.RemoveDirectory(path);
    }

    private static void DeleteTreeIfPresent(Ps5FtpClient ftp, string path)
    {
        try { _ = ftp.List(path); }
        catch (IOException ex) when (ex.Message.Contains("550", StringComparison.Ordinal)) { return; }
        DeleteTree(ftp, path);
    }

    private static void DeleteFileIfPresent(Ps5FtpClient ftp, string path)
    {
        try { if (ftp.GetSize(path) is not null) ftp.DeleteFile(path); }
        catch (IOException ex) when (ex.Message.Contains("550", StringComparison.Ordinal)) { }
    }

    private static void AddWorlds(Ps5FtpClient ftp, string savesPath, string version, List<WorldChoice> worlds)
    {
        try
        {
            foreach (var entry in ftp.List(savesPath).Where(x => x.IsDirectory))
                worlds.Add(new WorldChoice(entry.Name, version, savesPath + "/" + entry.Name));
        }
        catch { }
    }

    private static void DownloadWorld(Ps5FtpClient ftp, string remotePath, ZipArchive archive,
        string archiveRoot, Action<long> onProgress, string relative = "")
    {
        foreach (var entry in ftp.List(remotePath))
        {
            var childRemote = remotePath.TrimEnd('/') + "/" + entry.Name;
            var childRelative = string.IsNullOrEmpty(relative) ? entry.Name : relative + "/" + entry.Name;
            if (entry.IsDirectory)
            {
                DownloadWorld(ftp, childRemote, archive, archiveRoot, onProgress, childRelative);
            }
            else
            {
                var zipEntry = archive.CreateEntry(archiveRoot + "/" + childRelative, CompressionLevel.Fastest);
                using var destination = zipEntry.Open();
                ftp.Retrieve(childRemote, destination, onProgress);
            }
        }
    }

    private void EnsureChildOfGameSaves(string path)
    {
        var full = path.Replace('\\', '/');
        var allowed = (GamePath + "/saves/").ToLowerInvariant();
        if (!full.ToLowerInvariant().StartsWith(allowed, StringComparison.Ordinal)
            && !full.ToLowerInvariant().StartsWith((GamePath + "/instances/").ToLowerInvariant(), StringComparison.Ordinal)
            || !full.Contains("/saves/", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("The selected world path is outside JavaCraft saves.");
        if (full.Split('/').Any(p => p is "." or "..")) throw new InvalidOperationException("Invalid world path.");
    }

    private void BrowsePackage()
    {
        using var dialog = new FolderBrowserDialog { Description = "Choose the PS5\\data folder", UseDescriptionForTitle = true, ShowNewFolderButton = false };
        if (Directory.Exists(_packagePath.Text)) dialog.SelectedPath = _packagePath.Text;
        if (dialog.ShowDialog(this) == DialogResult.OK) _packagePath.Text = dialog.SelectedPath;
    }

    private void AutoFindPackage()
    {
        if (TryValidatePackage(_packagePath.Text, out _, out _, out _)) return;
        var exe = new DirectoryInfo(AppContext.BaseDirectory);
        for (var current = exe; current is not null; current = current.Parent)
        {
            var candidate = Path.Combine(current.FullName, "data");
            if (TryValidatePackage(candidate, out _, out _, out _)) { _packagePath.Text = candidate; return; }
            candidate = Path.Combine(current.FullName, "PS5", "data");
            if (TryValidatePackage(candidate, out _, out _, out _)) { _packagePath.Text = candidate; return; }
            candidate = Path.Combine(current.FullName, "dist", "PS5", "data");
            if (TryValidatePackage(candidate, out _, out _, out _)) { _packagePath.Text = candidate; return; }
        }
    }

    private static bool TryValidatePackage(string root, out string? app, out string? library, out string reason)
    {
        app = library = null;
        reason = "Select the complete data folder containing the JavaCraft app, emulator runtime, and Minecraft files.";
        if (!Directory.Exists(root)) return false;
        var appPath = Path.Combine(root, "homebrew", TitleId);
        var libraryPath = Path.Combine(root, "prospero-win");
        if (!File.Exists(Path.Combine(appPath, "eboot.bin"))) return false;
        if (!File.Exists(Path.Combine(appPath, "sce_sys", "param.json"))
            || !Directory.Exists(Path.Combine(appPath, "win")))
        {
            reason = "This package is missing the JavaCraft app metadata or bundled PS5 emulator runtime.";
            return false;
        }
        if (!Directory.Exists(Path.Combine(libraryPath, "prefixes", "minecraft-java"))) return false;
        if (!File.Exists(Path.Combine(libraryPath, "profiles", ProfileFile)))
        {
            reason = "This package is missing prospero-win\\profiles\\minecraft-java.profile. Rebuild or download the complete JavaCraft PS5 package.";
            return false;
        }
        var prefixPath = Path.Combine(libraryPath, "prefixes", "minecraft-java");
        var windowsPath = Path.Combine(prefixPath, "drive_c", "windows");
        var commonControls = Path.Combine(windowsPath, "winsxs",
            "amd64_microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef", "comctl32.dll");
        if (!File.Exists(Path.Combine(prefixPath, "system.reg"))
            || !File.Exists(Path.Combine(prefixPath, "user.reg"))
            || !File.Exists(Path.Combine(prefixPath, "userdef.reg"))
            || !File.Exists(Path.Combine(windowsPath, "system32", "kernel32.dll"))
            || !File.Exists(Path.Combine(windowsPath, "system32", "kernelbase.dll"))
            || !File.Exists(Path.Combine(windowsPath, "system32", "wowprospero.dll"))
            || !File.Exists(Path.Combine(windowsPath, "syswow64", "kernel32.dll"))
            || !File.Exists(Path.Combine(windowsPath, "syswow64", "kernelbase.dll"))
            || !File.Exists(commonControls)
            || !File.Exists(Path.Combine(prefixPath, "dosdevices", ".pw-symlinks")))
        {
            reason = "This package is missing the initialized Wine prefix required to start Minecraft. Rebuild it with tools/build.ps1 before installing.";
            return false;
        }
        var minecraftPath = Path.Combine(libraryPath, "prefixes", "minecraft-java", "drive_c", "JavaCraftPS5");
        if (!File.Exists(Path.Combine(minecraftPath, "runtime", "java-runtime-epsilon", "bin", "javaw.exe"))
            || !Directory.Exists(Path.Combine(minecraftPath, "assets"))
            || !Directory.Exists(Path.Combine(minecraftPath, "libraries"))
            || !Directory.Exists(Path.Combine(minecraftPath, "versions"))
            || !Directory.Exists(Path.Combine(minecraftPath, "natives"))
            || !Directory.Exists(Path.Combine(minecraftPath, "game", "mods")))
        {
            reason = "This is not a complete JavaCraft package. The bundled Java runtime, assets, libraries, versions, natives, or mods folder is missing.";
            return false;
        }
        app = appPath;
        library = libraryPath;
        reason = "";
        return true;
    }

    private static bool IsProtectedMod(string name)
    {
        var normalized = name.Replace(" ", "", StringComparison.Ordinal)
            .Replace("_", "", StringComparison.Ordinal)
            .Replace("-", "", StringComparison.Ordinal).ToLowerInvariant();
        return normalized.Contains("javacraft", StringComparison.Ordinal)
            || normalized.Contains("blackshield", StringComparison.Ordinal)
            || normalized.Contains("controlifyjavacraft", StringComparison.Ordinal);
    }

    private static bool IsProtectedModArchive(string path)
    {
        if (IsProtectedMod(Path.GetFileName(path))) return true;
        try
        {
            using var archive = ZipFile.OpenRead(path);
            var metadata = archive.GetEntry("fabric.mod.json");
            if (metadata is null) return false;
            using var stream = metadata.Open();
            using var document = JsonDocument.Parse(stream);
            if (!document.RootElement.TryGetProperty("id", out var idValue)) return false;
            var id = idValue.GetString()?.Replace("_", "", StringComparison.Ordinal)
                .Replace("-", "", StringComparison.Ordinal).ToLowerInvariant();
            return id is "javacraftaccount" or "blackshieldcommunity" or "controlify" or "controlifyjavacraft";
        }
        catch { return false; }
    }

    private static void StyleList(ListView list)
    {
        list.BackColor = Surface;
        list.ForeColor = TextMain;
        list.BorderStyle = BorderStyle.None;
        list.Font = new Font("Segoe UI", 10f);
        list.HideSelection = false;
        list.OwnerDraw = true;
        list.DrawColumnHeader += (_, e) => { using var brush = new SolidBrush(Panel2); using var font = new Font(list.Font, FontStyle.Bold); e.Graphics.FillRectangle(brush, e.Bounds); TextRenderer.DrawText(e.Graphics, e.Header?.Text ?? "", font, e.Bounds, TextMuted, TextFormatFlags.VerticalCenter | TextFormatFlags.Left); };
        list.DrawItem += (_, e) => { };
        list.DrawSubItem += (_, e) =>
        {
            using var brush = new SolidBrush(e.Item?.Selected == true ? Color.FromArgb(39, 67, 78) : Surface);
            e.Graphics.FillRectangle(brush, e.Bounds);
            TextRenderer.DrawText(e.Graphics, e.SubItem?.Text ?? "", list.Font, new Rectangle(e.Bounds.X + 8, e.Bounds.Y, e.Bounds.Width - 12, e.Bounds.Height), e.Item?.ForeColor ?? TextMain, TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.Left);
        };
    }

    private static TabPage NewTab(string text) => new(text) { BackColor = Surface, ForeColor = TextMain, Padding = new Padding(0) };

    private static void ConfigureLog(TextBox box)
    {
        box.Multiline = true;
        box.ReadOnly = true;
        box.ScrollBars = ScrollBars.Vertical;
        box.BackColor = Surface;
        box.ForeColor = TextMain;
        box.BorderStyle = BorderStyle.None;
        box.Font = new Font("Cascadia Mono", 9.5f);
        box.Dock = DockStyle.Fill;
    }

    private static void StyleTextBox(TextBox box)
    {
        box.BackColor = Panel2;
        box.ForeColor = TextMain;
        box.BorderStyle = BorderStyle.FixedSingle;
        box.Font = new Font("Segoe UI", 10f);
        box.Margin = new Padding(4, 2, 8, 0);
    }

    private static void StyleCombo(ComboBox combo)
    {
        combo.BackColor = Panel2;
        combo.ForeColor = TextMain;
        combo.FlatStyle = FlatStyle.Flat;
        combo.Font = new Font("Segoe UI", 9.5f);
        combo.Margin = new Padding(4, 2, 12, 0);
    }

    private static void StyleButton(Button button, Color background, Color foreground)
    {
        button.FlatStyle = FlatStyle.Flat;
        button.FlatAppearance.BorderSize = 0;
        button.BackColor = background;
        button.ForeColor = foreground;
        button.Font = new Font("Segoe UI Semibold", 9.5f);
        button.Cursor = Cursors.Hand;
        button.Margin = new Padding(8, 0, 0, 0);
    }

    private static Button NewActionButton(string text, Color color, int width)
    {
        var button = new Button { Text = text, Width = width, Height = 34 };
        StyleButton(button, color, color == Accent ? Color.FromArgb(12, 30, 25) : TextMain);
        return button;
    }

    private static Label SmallLabel(string text, int width) => new()
    {
        Text = text,
        Width = width,
        Height = 34,
        TextAlign = ContentAlignment.MiddleLeft,
        ForeColor = TextMuted,
        Margin = new Padding(0, 0, 3, 0)
    };

    private static string FormatSize(long value)
    {
        string[] suffix = ["B", "KB", "MB", "GB", "TB"];
        double size = value;
        var unit = 0;
        while (size >= 1024 && unit < suffix.Length - 1) { size /= 1024; unit++; }
        return size.ToString(size >= 100 || unit == 0 ? "0" : "0.0", System.Globalization.CultureInfo.InvariantCulture) + " " + suffix[unit];
    }

    private void SetBusy(bool busy, string? message = null)
    {
        _busy = busy;
        UseWaitCursor = busy;
        _connectButton.Enabled = !busy;
        _hostBox.Enabled = !busy;
        _portBox.Enabled = !busy;
        _body.Enabled = !busy;
        UpdateModActions();
        _progressBar.Visible = busy;
        _progressBar.Style = busy ? ProgressBarStyle.Marquee : ProgressBarStyle.Blocks;
        if (!busy) { _progressText.Text = ""; _progressBar.Value = 0; }
        else { _progressText.Text = message ?? "Working…"; _progressBar.Value = 0; }
    }

    private bool TryGetTarget(out string host, out int port)
    {
        host = _hostBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(host))
        {
            MessageBox.Show(this, "Enter the PS5 IP address first.", "PS5 connection", MessageBoxButtons.OK, MessageBoxIcon.Information);
            port = 0;
            return false;
        }
        if (!IPAddress.TryParse(host, out var address) || !IsPrivateAddress(address))
        {
            MessageBox.Show(this, "Enter the PS5's private IPv4 address on your local network (for example, 192.168.8.33).", "PS5 connection", MessageBoxButtons.OK, MessageBoxIcon.Information);
            port = 0;
            return false;
        }
        if (!int.TryParse(_portBox.Text.Trim(), out port) || port is < 1 or > 65535)
        {
            MessageBox.Show(this, "Enter a valid FTP port. The default PS5 port is 2121.", "PS5 connection", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return false;
        }
        return true;
    }

    private static bool IsPrivateAddress(IPAddress address)
    {
        if (address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) return false;
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10
            || bytes[0] == 192 && bytes[1] == 168
            || bytes[0] == 172 && bytes[1] is >= 16 and <= 31
            || bytes[0] == 169 && bytes[1] == 254
            || bytes[0] == 127;
    }

    private void ShowError(string message, Exception ex)
    {
        _footerStatus.Text = "Operation failed · see details";
        if (_operationLog.IsHandleCreated) LogLine(_operationLog, "ERROR: " + ex.Message);
        MessageBox.Show(this, message + "\n\n" + ex.Message, "JavaCraft PS5 Uploader", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }

    private static void LogLine(TextBox box, string line)
    {
        if (box.InvokeRequired) { box.BeginInvoke(() => LogLine(box, line)); return; }
        box.AppendText("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + line + Environment.NewLine);
    }

    private void LoadSettings()
    {
        try
        {
            if (File.Exists(SettingsFile))
            {
                var saved = JsonSerializer.Deserialize<SavedSettings>(File.ReadAllText(SettingsFile));
                if (saved is not null) { _hostBox.Text = saved.Host; _portBox.Text = saved.Port; }
            }
        }
        catch { }
        if (string.IsNullOrWhiteSpace(_hostBox.Text)) _hostBox.Text = "192.168.8.33";
        if (string.IsNullOrWhiteSpace(_portBox.Text)) _portBox.Text = "2121";
    }

    private void SaveSettings()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(SettingsFile)!);
            File.WriteAllText(SettingsFile, JsonSerializer.Serialize(new SavedSettings(_hostBox.Text.Trim(), _portBox.Text.Trim())));
        }
        catch { }
    }

    private static void LogLine(TextBox? box, string line, bool unused = false) { if (box is not null) LogLine(box, line); }

}
