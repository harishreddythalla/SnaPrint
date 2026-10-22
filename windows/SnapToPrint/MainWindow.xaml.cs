using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using SnapToPrint.Core;

namespace SnapToPrint;

public partial class MainWindow : Window
{
    private readonly ConfigStore _cfg;
    private readonly ReceiptDb _db;
    private readonly AppLog _log;
    private readonly SyncEngine _engine;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly string _logDir;
    private System.Windows.Forms.NotifyIcon? _tray;
    private bool _exiting;
    private string[] _lastErrors = Array.Empty<string>();

    public MainWindow()
    {
        InitializeComponent();
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SnapToPrint");
        _logDir = Path.Combine(dir, "logs");
        _log = new AppLog(_logDir);
        _cfg = new ConfigStore(dir);
        _db = new ReceiptDb(Path.Combine(dir, "receipts.db"));
        _engine = new SyncEngine(_cfg, _db, _log);

        DestBox.Text = _cfg.DestRoot;
        IpBox.Text = _cfg.ManualIp;
        SetupExpander.IsExpanded = string.IsNullOrEmpty(_cfg.Token);

        _tray = new System.Windows.Forms.NotifyIcon
        {
            Icon = System.Drawing.SystemIcons.Application,
            Visible = true,
            Text = "Snap-to-Print"
        };
        _tray.DoubleClick += (_, _) => ShowFromTray();
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => ShowFromTray());
        menu.Items.Add("Exit", null, (_, _) => ExitApp());
        _tray.ContextMenuStrip = menu;

        _timer.Tick += (_, _) => Refresh();
        _timer.Start();
        _engine.Start();
        Refresh();
    }

    private void Refresh()
    {
        var s = _engine.Status;
        StatusText.Text = s.Headline;
        StatusBorder.Background = s.Level switch
        {
            Level.Good => Brushes.SeaGreen,
            Level.Warn => Brushes.DarkOrange,
            _ => Brushes.Firebrick
        };
        var last = s.LastReceiptUtc?.ToLocalTime().ToString("HH:mm:ss") ?? "-";
        DetailText.Text =
            $"Phone: {s.PhoneAddress}\nEvent: {s.EventName}\nFolder: {(string.IsNullOrEmpty(s.EventFolder) ? "-" : s.EventFolder)}\n" +
            $"Received: {s.Received}    Pending: {s.Pending}    Failed: {s.Failed}\nLast receipt: {last}";
        var errs = _log.RecentErrors();
        if (!errs.SequenceEqual(_lastErrors))
        {
            ErrorList.ItemsSource = errs;
            _lastErrors = errs;
        }
        if (_tray != null) _tray.Text = s.Headline.Length > 63 ? s.Headline.Substring(0, 63) : s.Headline;
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        var f = _engine.Status.EventFolder;
        var target = !string.IsNullOrEmpty(f) && Directory.Exists(f) ? f : _cfg.DestRoot;
        try
        {
            Directory.CreateDirectory(target);
            Process.Start(new ProcessStartInfo("explorer.exe", "\"" + target + "\"") { UseShellExecute = true });
        }
        catch (Exception ex) { MessageBox.Show("Could not open folder: " + ex.Message); }
    }

    private void Reconnect_Click(object sender, RoutedEventArgs e) => _engine.Reconnect();

    private async void Test_Click(object sender, RoutedEventArgs e)
    {
        TestBtn.IsEnabled = false;
        try
        {
            var text = await _engine.DiagnoseAsync();
            _log.Info("diagnostic:\n" + text);
            MessageBox.Show(text, "Connection test");
        }
        finally { TestBtn.IsEnabled = true; }
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        _cfg.Save(DestBox.Text, PairBox.Text, IpBox.Text);
        PairBox.Text = "";
        _engine.Reconnect();
        MessageBox.Show("Saved.");
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog();
        if (dlg.ShowDialog() == true) DestBox.Text = dlg.FolderName;
    }

    private void OpenLogs_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(_logDir);
            Process.Start(new ProcessStartInfo("explorer.exe", "\"" + _logDir + "\"") { UseShellExecute = true });
        }
        catch (Exception ex) { MessageBox.Show(ex.Message); }
    }

    private void ShowFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void ExitApp()
    {
        _exiting = true;
        _engine.Stop();
        if (_tray != null) { _tray.Visible = false; _tray.Dispose(); }
        System.Windows.Application.Current.Shutdown();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_exiting)
        {
            e.Cancel = true; // keep receiving photos: hide to the tray instead of quitting
            Hide();
            _tray?.ShowBalloonTip(2000, "Snap-to-Print", "Still running in the tray. Right-click the icon to exit.", System.Windows.Forms.ToolTipIcon.Info);
        }
        base.OnClosing(e);
    }
}
