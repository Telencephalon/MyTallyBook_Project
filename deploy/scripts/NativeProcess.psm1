Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($null -eq ('MyTallyBook.NativeProcessApi' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace MyTallyBook {
    public sealed class ProcessMetadata {
        public int ProcessId { get; set; }
        public string ExecutablePath { get; set; }
        public string CommandLine { get; set; }
        public long CreationTimeFileTime { get; set; }
    }

    public static class NativeProcessApi {
        const uint PROCESS_TERMINATE = 0x0001;
        const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        const uint SYNCHRONIZE = 0x00100000;
        const int ProcessCommandLineInformation = 60;
        const int STATUS_INFO_LENGTH_MISMATCH = unchecked((int)0xC0000004);
        const uint WAIT_OBJECT_0 = 0x00000000;
        const uint WAIT_TIMEOUT = 0x00000102;
        const uint WAIT_FAILED = 0xFFFFFFFF;

        [StructLayout(LayoutKind.Sequential)]
        struct UNICODE_STRING {
            public ushort Length;
            public ushort MaximumLength;
            public IntPtr Buffer;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct FILETIME { public uint Low; public uint High; }

        [DllImport("kernel32.dll", SetLastError=true)]
        static extern IntPtr OpenProcess(uint access, bool inheritHandle, int processId);
        [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
        static extern bool QueryFullProcessImageName(IntPtr process, int flags, StringBuilder name, ref int size);
        [DllImport("kernel32.dll", SetLastError=true)]
        static extern bool GetProcessTimes(IntPtr process, out FILETIME creation, out FILETIME exit, out FILETIME kernel, out FILETIME user);
        [DllImport("kernel32.dll", SetLastError=true)]
        static extern bool TerminateProcess(IntPtr process, uint exitCode);
        [DllImport("kernel32.dll", SetLastError=true)]
        static extern bool CloseHandle(IntPtr handle);
        [DllImport("kernel32.dll", SetLastError=true)]
        static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
        [DllImport("ntdll.dll")]
        static extern int NtQueryInformationProcess(IntPtr process, int infoClass, IntPtr info, int infoLength, out int returnLength);

        public static IntPtr Open(int processId, bool terminate) {
            uint access = PROCESS_QUERY_LIMITED_INFORMATION | (terminate ? PROCESS_TERMINATE | SYNCHRONIZE : 0);
            IntPtr handle = OpenProcess(access, false, processId);
            if (handle == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            return handle;
        }

        static string ReadCommandLine(IntPtr handle) {
            int needed;
            int status = NtQueryInformationProcess(handle, ProcessCommandLineInformation, IntPtr.Zero, 0, out needed);
            if (needed <= 0 || needed > 1024 * 1024 || (status != STATUS_INFO_LENGTH_MISMATCH && status < 0))
                throw new Win32Exception("Process command line is unavailable.");
            for (int attempt = 0; attempt < 3; attempt++) {
                IntPtr memory = Marshal.AllocHGlobal(needed);
                try {
                    status = NtQueryInformationProcess(handle, ProcessCommandLineInformation, memory, needed, out needed);
                    if (status == STATUS_INFO_LENGTH_MISMATCH) {
                        if (needed <= 0 || needed > 1024 * 1024) throw new Win32Exception("Process command line is too large.");
                        continue;
                    }
                    if (status < 0) throw new Win32Exception("Process command line query failed.");
                    UNICODE_STRING value = (UNICODE_STRING)Marshal.PtrToStructure(memory, typeof(UNICODE_STRING));
                    long start = memory.ToInt64();
                    long pointer = value.Buffer.ToInt64();
                    if ((value.Length & 1) != 0 || value.Length > value.MaximumLength || pointer < start || pointer + value.Length > start + needed)
                        throw new Win32Exception("Process command line data is invalid.");
                    return Marshal.PtrToStringUni(value.Buffer, value.Length / 2);
                } finally { Marshal.FreeHGlobal(memory); }
            }
            throw new Win32Exception("Process command line changed during inspection.");
        }

        public static ProcessMetadata Read(IntPtr handle, int processId) {
            var name = new StringBuilder(32768);
            int size = name.Capacity;
            if (!QueryFullProcessImageName(handle, 0, name, ref size)) throw new Win32Exception(Marshal.GetLastWin32Error());
            FILETIME creation, exit, kernel, user;
            if (!GetProcessTimes(handle, out creation, out exit, out kernel, out user)) throw new Win32Exception(Marshal.GetLastWin32Error());
            return new ProcessMetadata {
                ProcessId = processId,
                ExecutablePath = name.ToString(),
                CommandLine = ReadCommandLine(handle),
                CreationTimeFileTime = unchecked((long)(((ulong)creation.High << 32) | creation.Low))
            };
        }

        public static void Terminate(IntPtr handle) {
            if (!TerminateProcess(handle, 1)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        public static bool WaitExited(IntPtr handle, int milliseconds) {
            uint result = WaitForSingleObject(handle, (uint)milliseconds);
            if (result == WAIT_OBJECT_0) return true;
            if (result == WAIT_TIMEOUT) return false;
            if (result == WAIT_FAILED) throw new Win32Exception(Marshal.GetLastWin32Error());
            throw new Win32Exception("Unexpected process wait result.");
        }
        public static void Close(IntPtr handle) { if (handle != IntPtr.Zero) CloseHandle(handle); }
    }
}
'@
}

function Get-LimitedProcessMetadata {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Process, [switch]$RequirePreciseCreationTime)
    $processId = [int]$Process.ProcessId
    if ($Process.PSObject.Properties['CreationTimeFileTime'] -and [int64]$Process.CreationTimeFileTime -gt 0 -and $Process.ExecutablePath -and $Process.CommandLine) {
        return [pscustomobject]@{ ProcessId = $processId; ExecutablePath = [string]$Process.ExecutablePath; CommandLine = [string]$Process.CommandLine; CreationTimeFileTime = [int64]$Process.CreationTimeFileTime }
    }
    if (-not $RequirePreciseCreationTime -and $Process.ExecutablePath -and $Process.CommandLine) {
        return [pscustomobject]@{ ProcessId = $processId; ExecutablePath = [string]$Process.ExecutablePath; CommandLine = [string]$Process.CommandLine; CreationTimeFileTime = 0L }
    }
    $handle = [IntPtr]::Zero
    try {
        $handle = Open-NativeProcessHandle -ProcessId $processId -Terminate $false
        return Read-NativeProcessHandle -Handle $handle -ProcessId $processId
    } catch { return $null }
    finally { Close-NativeProcessHandle -Handle $handle }
}

function Open-NativeProcessHandle([int]$ProcessId, [bool]$Terminate) { return [MyTallyBook.NativeProcessApi]::Open($ProcessId, $Terminate) }
function Read-NativeProcessHandle([IntPtr]$Handle, [int]$ProcessId) { return [MyTallyBook.NativeProcessApi]::Read($Handle, $ProcessId) }
function Terminate-NativeProcessHandle([IntPtr]$Handle) { [MyTallyBook.NativeProcessApi]::Terminate($Handle) }
function Wait-NativeProcessHandle([IntPtr]$Handle, [int]$Milliseconds) { return [MyTallyBook.NativeProcessApi]::WaitExited($Handle, $Milliseconds) }
function Close-NativeProcessHandle([IntPtr]$Handle) { [MyTallyBook.NativeProcessApi]::Close($Handle) }

function Test-ExactJavaJarProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Process,
        [Parameter(Mandatory)][string]$JavaPath,
        [Parameter(Mandatory)][string]$JarPath
    )
    if ($null -eq $Process -or -not $Process.ExecutablePath -or -not $Process.CommandLine) { return $false }
    try {
        $java = [IO.Path]::GetFullPath($JavaPath)
        $jar = [IO.Path]::GetFullPath($JarPath)
        if ([IO.Path]::GetFullPath($Process.ExecutablePath) -ine $java) { return $false }
        if ($Process.CommandLine -notmatch '^(?:"(?<exe>[^"]+)"|(?<exe>\S+))\s+-jar\s+"(?<jar>[^"]+)"\s*$') { return $false }
        foreach ($path in @($Matches.exe, $Matches.jar)) {
            if (-not [IO.Path]::IsPathRooted($path) -or [IO.Path]::GetPathRoot($path).Length -lt 3) { return $false }
        }
        return ([IO.Path]::GetFullPath($Matches.exe) -ieq $java -and [IO.Path]::GetFullPath($Matches.jar) -ieq $jar)
    } catch { return $false }
}

function Stop-ExactNativeProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Expected,
        [scriptblock]$IdentityValidator,
        [string]$ExpectedJavaPath,
        [string]$ExpectedJarPath,
        [ValidateRange(1, 600)][int]$TimeoutSeconds = 15
    )
    if ([int64]$Expected.CreationTimeFileTime -le 0) { throw 'BackendIdentityUnavailable' }
    $handle = [IntPtr]::Zero
    try {
        try { $handle = Open-NativeProcessHandle -ProcessId ([int]$Expected.ProcessId) -Terminate $true }
        catch { throw 'BackendTerminationUnavailable' }
        try { $current = Read-NativeProcessHandle -Handle $handle -ProcessId ([int]$Expected.ProcessId) }
        catch { throw 'BackendIdentityUnavailable' }
        $identityMatches = if ($null -ne $IdentityValidator) { & $IdentityValidator $current }
            elseif ($ExpectedJavaPath -and $ExpectedJarPath) { Test-ExactJavaJarProcess -Process $current -JavaPath $ExpectedJavaPath -JarPath $ExpectedJarPath }
            else { $false }
        if ([int64]$current.CreationTimeFileTime -ne [int64]$Expected.CreationTimeFileTime -or -not $identityMatches) { throw 'BackendIdentityChanged' }
        try { Terminate-NativeProcessHandle -Handle $handle }
        catch { throw 'BackendTerminationFailed' }
        try { $exited = Wait-NativeProcessHandle -Handle $handle -Milliseconds ($TimeoutSeconds * 1000) }
        catch { throw 'BackendWaitFailed' }
        if (-not $exited) { throw 'BackendStopTimeout' }
    } finally { Close-NativeProcessHandle -Handle $handle }
}

Export-ModuleMember -Function Get-LimitedProcessMetadata, Stop-ExactNativeProcess, Test-ExactJavaJarProcess
