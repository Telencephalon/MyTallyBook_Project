#Requires -RunAsAdministrator

[CmdletBinding()]
param(
    [string]$JdkHome = 'D:\Work\Config\JDK\JDK\jdk21'
)

$resolvedJdkHome = [System.IO.Path]::GetFullPath($JdkHome).TrimEnd('\')
$javaExecutable = Join-Path $resolvedJdkHome 'bin\java.exe'
$javacExecutable = Join-Path $resolvedJdkHome 'bin\javac.exe'

if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "java.exe not found: $javaExecutable"
}

if (-not (Test-Path -LiteralPath $javacExecutable -PathType Leaf)) {
    throw "javac.exe not found: $javacExecutable"
}

$environmentKeyPath = 'SYSTEM\CurrentControlSet\Control\Session Manager\Environment'
$environmentKey = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($environmentKeyPath, $true)

if ($null -eq $environmentKey) {
    throw "Cannot open machine environment registry key: $environmentKeyPath"
}

try {
    $oldJavaHome = [string]$environmentKey.GetValue('JAVA_HOME', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    $machinePath = [string]$environmentKey.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    $targetJavaBin = Join-Path $resolvedJdkHome 'bin'
    $oldJavaBin = if ($oldJavaHome) { Join-Path $oldJavaHome.TrimEnd('\') 'bin' } else { '' }

    $keptPathEntries = foreach ($pathEntry in ($machinePath -split ';')) {
        $trimmedPathEntry = $pathEntry.Trim()
        if (-not $trimmedPathEntry) {
            continue
        }

        if ($trimmedPathEntry.Equals('%JAVA_HOME%\bin', [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }

        if ($oldJavaBin -and $trimmedPathEntry.TrimEnd('\').Equals($oldJavaBin.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }

        if ($trimmedPathEntry.TrimEnd('\').Equals($targetJavaBin.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }

        $trimmedPathEntry
    }

    $newMachinePath = (@('%JAVA_HOME%\bin') + $keptPathEntries) -join ';'
    $environmentKey.SetValue('JAVA_HOME', $resolvedJdkHome, [Microsoft.Win32.RegistryValueKind]::String)
    $environmentKey.SetValue('Path', $newMachinePath, [Microsoft.Win32.RegistryValueKind]::ExpandString)
}
finally {
    $environmentKey.Dispose()
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class EnvironmentChangeBroadcaster
{
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr SendMessageTimeout(
        IntPtr hWnd,
        uint msg,
        UIntPtr wParam,
        string lParam,
        uint flags,
        uint timeout,
        out UIntPtr result);

    public static void Broadcast()
    {
        UIntPtr result;
        SendMessageTimeout(new IntPtr(0xffff), 0x001A, UIntPtr.Zero, "Environment", 0x0002, 5000, out result);
    }
}
'@

[EnvironmentChangeBroadcaster]::Broadcast()

Write-Output "JAVA_HOME=$resolvedJdkHome"
Write-Output 'Machine Path starts with %JAVA_HOME%\bin'

