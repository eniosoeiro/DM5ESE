// Windows .NET Framework launcher. Original GE binary is never modified.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

static class UltraMateLauncher
{
    const string Target = @"C:\Program Files (x86)\UltraMATE Lite\UltraMATE Lite.exe";
    static readonly string LogPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "ultima-abertura.log");
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct Mode
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string device;
        public ushort spec, driver, size, extra;
        public uint fields;
        public int x, y;
        public uint orientation, fixedOutput;
        public short color, duplex, yres, tt, collate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string form;
        public ushort logPixels;
        public uint bits, width, height, flags, frequency, icmMethod, icmIntent, media, dither, reserved1, reserved2, panningWidth, panningHeight;
    }
    delegate bool WindowCallback(IntPtr window, IntPtr parameter);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplaySettings(string device, int index, ref Mode mode);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string device, ref Mode mode, IntPtr window, uint flags, IntPtr parameter);
    [DllImport("user32.dll")] static extern bool EnumWindows(WindowCallback callback, IntPtr parameter);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr window, StringBuilder name, int capacity);
    [DllImport("user32.dll")] static extern bool ShowWindowAsync(IntPtr window, int command);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);

    static void Log(string message) { File.AppendAllText(LogPath, DateTime.Now.ToString("s") + " " + message + Environment.NewLine); }
    static IntPtr MainWindow(int processId)
    {
        IntPtr found = IntPtr.Zero;
        EnumWindows(delegate(IntPtr window, IntPtr unused) {
            uint owner;
            GetWindowThreadProcessId(window, out owner);
            if (owner != processId) return true;
            var name = new StringBuilder(128);
            GetClassName(window, name, name.Capacity);
            if (name.ToString() != "ThunderRT6MDIForm") return true;
            found = window;
            return false;
        }, IntPtr.Zero);
        return found;
    }

    [STAThread]
    static int Main()
    {
        using (var mutex = new Mutex(false, @"Local\DM5ESE.UltraMateLauncher"))
        {
            bool acquired;
            try { acquired = mutex.WaitOne(0); }
            catch (AbandonedMutexException) { acquired = true; }
            if (!acquired) return 0;
            try { return Run(); }
            catch (Exception error)
            {
                try { Log("ERROR " + error.Message); } catch { }
                MessageBox.Show(error.Message, "Abertura do UltraMATE", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
            finally { mutex.ReleaseMutex(); }
        }
    }

    static int Run()
    {
        if (!File.Exists(Target)) throw new FileNotFoundException("UltraMATE Lite não encontrado.", Target);
        foreach (var existing in Process.GetProcessesByName("UltraMATE Lite"))
        {
            using (existing)
            {
                if (!String.Equals(existing.MainModule.FileName, Target, StringComparison.OrdinalIgnoreCase)) continue;
                var window = MainWindow(existing.Id);
                if (window == IntPtr.Zero) throw new InvalidOperationException("Feche a janela de erro do UltraMATE e abra este atalho novamente.");
                ShowWindowAsync(window, 9);
                SetForegroundWindow(window);
                Log("Already running PID=" + existing.Id);
                return 0;
            }
        }
        string display = Screen.PrimaryScreen.DeviceName;
        var original = new Mode();
        original.size = (ushort)Marshal.SizeOf(original);
        if (!EnumDisplaySettings(display, -1, ref original)) throw new InvalidOperationException("Não foi possível consultar a resolução atual.");
        Log("Original=" + original.width + "x" + original.height);
        bool changed = false;
        IntPtr mainWindow = IntPtr.Zero;
        try
        {
            // VB6 stores screen dimensions as signed 16-bit twips at startup.
            if (original.width > 1920 || original.height > 1920)
            {
                var temporary = original;
                temporary.width = 1920;
                temporary.height = 1080;
                temporary.fields = 0x180000; // DM_PELSWIDTH | DM_PELSHEIGHT
                if (ChangeDisplaySettingsEx(display, ref temporary, IntPtr.Zero, 2, IntPtr.Zero) != 0)
                    throw new InvalidOperationException("Este monitor não aceita o modo temporário de 1920x1080.");
                if (ChangeDisplaySettingsEx(display, ref temporary, IntPtr.Zero, 0, IntPtr.Zero) != 0)
                    throw new InvalidOperationException("Não foi possível aplicar a resolução temporária.");
                changed = true;
                Thread.Sleep(1500);
            }
            var start = new ProcessStartInfo(Target) {
                WorkingDirectory = Path.GetDirectoryName(Target),
                UseShellExecute = false,
                WindowStyle = ProcessWindowStyle.Normal
            };
            using (var app = Process.Start(start))
            {
                Log("Started PID=" + app.Id);
                var timer = Stopwatch.StartNew();
                while (timer.Elapsed.TotalSeconds < 30)
                {
                    if (app.HasExited) throw new InvalidOperationException("O UltraMATE encerrou durante a abertura.");
                    mainWindow = MainWindow(app.Id);
                    if (mainWindow != IntPtr.Zero) break;
                    Thread.Sleep(250);
                }
                if (mainWindow == IntPtr.Zero) throw new TimeoutException("A janela principal não abriu em 30 segundos. Confira se há uma mensagem de erro no UltraMATE.");
                Thread.Sleep(2000);
                Log("Main window ready");
            }
        }
        finally
        {
            if (changed)
            {
                int result = ChangeDisplaySettingsEx(display, ref original, IntPtr.Zero, 0, IntPtr.Zero);
                Log("Restore=" + result);
                if (result != 0) throw new InvalidOperationException("O Windows não restaurou a resolução. Restaure " + original.width + "x" + original.height + " nas configurações de Tela.");
            }
        }
        ShowWindowAsync(mainWindow, 9);
        SetForegroundWindow(mainWindow);
        return 0;
    }
}
