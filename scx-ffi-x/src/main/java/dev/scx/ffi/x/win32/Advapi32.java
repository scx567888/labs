package dev.scx.ffi.x.win32;

import dev.scx.ffi.ScxFFI;
import dev.scx.ffi.type.IntRef;

import java.lang.foreign.MemorySegment;

import static dev.scx.ffi.x.win32.WinBase.*;

/// 提供一些 Advapi32 标准的接口
///
/// @author scx567888
/// @version 0.0.1
public interface Advapi32 {

    Advapi32 ADVAPI32 = ScxFFI.createFFI(Advapi32.class,"Advapi32");

    int RegOpenKeyExA(int hKey, String lpSubKey, int ulOptions, int samDesired, IntRef phkResult);

    int RegCloseKey(int hKey);

    int RegEnumValueW(int hKey, int dwIndex, MemorySegment lpValueName, IntRef lpcchValueName, IntRef lpReserved, IntRef lpType, MemorySegment lpData, IntRef lpcbData);

    int RegQueryInfoKeyW(int hKey, char[] lpClass, IntRef lpcchClass, IntRef lpReserved, IntRef lpcSubKeys, IntRef lpcbMaxSubKeyLen, IntRef lpcbMaxClassLen, IntRef lpcValues, IntRef lpcbMaxValueNameLen, IntRef lpcbMaxValueLen, IntRef lpcbSecurityDescriptor, FILETIME lpftLastWriteTime);

    int RegQueryValueExA(int hKey, String lpValueName, int lpReserved, IntRef lpType, IntRef lpData, IntRef lpcbData);

    int RegQueryValueExA(int hKey, String lpValueName, int lpReserved, IntRef lpType, MemorySegment lpData, IntRef lpcbData);

    int RegSetValueExA(int hKey, String lpValueName, int Reserved, int dwType, MemorySegment lpData, int cbData);

}
