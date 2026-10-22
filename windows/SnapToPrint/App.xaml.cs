using System.Threading;
using System.Windows;

namespace SnapToPrint;

public partial class App : Application
{
    private static Mutex? _mutex;

    protected override void OnStartup(StartupEventArgs e)
    {
        _mutex = new Mutex(true, "SnapToPrint.SingleInstance", out bool created);
        if (!created)
        {
            MessageBox.Show("Snap-to-Print is already running (look in the system tray).");
            Shutdown();
            return;
        }
        base.OnStartup(e);
    }
}
