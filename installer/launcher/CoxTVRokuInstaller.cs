// "Install CoxTV on Roku.exe" - a tiny double-clickable launcher for files\Install-CoxTV-Roku.ps1.
// Built by scripts\release.ps1 with the C# compiler that ships with Windows (.NET Framework 4).
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;

[assembly: AssemblyTitle("CoxTV Roku Installer")]
[assembly: AssemblyDescription("Installs the CoxTV app on a Roku in Developer Mode")]
[assembly: AssemblyProduct("CoxTV")]
[assembly: AssemblyCompany("CoxTV")]
[assembly: AssemblyCopyright("CoxTV")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

internal static class Program
{
    private static int Main()
    {
        Console.Title = "CoxTV Roku Installer";
        string folder = AppDomain.CurrentDomain.BaseDirectory;
        string script = Path.Combine(Path.Combine(folder, "files"), "Install-CoxTV-Roku.ps1");

        if (!File.Exists(script))
        {
            Console.WriteLine();
            Console.WriteLine("  The installer's 'files' folder wasn't found next to this program.");
            Console.WriteLine();
            Console.WriteLine("  If you opened this straight from the downloaded .zip file:");
            Console.WriteLine("    1. Close this window.");
            Console.WriteLine("    2. Right-click the .zip file and choose 'Extract All...', then 'Extract'.");
            Console.WriteLine("    3. Open the extracted 'CoxTV Roku Installer' folder and double-click");
            Console.WriteLine("       'Install CoxTV on Roku' again.");
            return Pause(1);
        }

        string powershell = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),
                                         @"WindowsPowerShell\v1.0\powershell.exe");
        if (!File.Exists(powershell)) powershell = "powershell.exe";

        var start = new ProcessStartInfo(powershell,
            "-NoProfile -ExecutionPolicy Bypass -File \"" + script + "\"")
        {
            UseShellExecute = false,
            WorkingDirectory = folder,
        };
        try
        {
            using (Process process = Process.Start(start))
            {
                process.WaitForExit();
                return Pause(process.ExitCode);
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine();
            Console.WriteLine("  Couldn't start Windows PowerShell: " + ex.Message);
            return Pause(1);
        }
    }

    private static int Pause(int code)
    {
        Console.WriteLine();
        Console.Write("  Press any key to close this window...");
        try { Console.ReadKey(true); } catch (InvalidOperationException) { }
        return code;
    }
}
