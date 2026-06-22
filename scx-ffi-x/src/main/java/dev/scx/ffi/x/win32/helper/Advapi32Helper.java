package dev.scx.ffi.x.win32.helper;

import dev.scx.ffi.type.IntRef;
import dev.scx.ffi.x.win32.Win32Exception;
import dev.scx.ffi.x.win32.WinError;
import dev.scx.ffi.x.win32.WinNT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TreeMap;

import static dev.scx.ffi.x.win32.Advapi32.ADVAPI32;
import static dev.scx.ffi.x.win32.WinNT.*;


/// 简化操作 注册表
/// todo 代码待整理
///
/// @author scx567888
/// @version 0.0.1
public final class Advapi32Helper {

    public static TreeMap<String, Object> registryGetValues(int root, String keyPath) {
        return registryGetValues(root, keyPath, 0);
    }

    public static TreeMap<String, Object> registryGetValues(int root, String keyPath, int samDesiredExtra) {
        IntRef phkKey = new IntRef();
        int rc = ADVAPI32.RegOpenKeyExA(root, keyPath, 0,
                WinNT.KEY_READ | samDesiredExtra, phkKey);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new Win32Exception(rc);
        }
        try {
            return registryGetValues(phkKey.value());
        } finally {
            rc = ADVAPI32.RegCloseKey(phkKey.value());
            if (rc != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(rc);
            }
        }
    }

    public static TreeMap<String, Object> registryGetValues(int hKey) {
        IntRef lpcValues = new IntRef();
        IntRef lpcMaxValueNameLen = new IntRef();
        IntRef lpcMaxValueLen = new IntRef();
        int rc = ADVAPI32.RegQueryInfoKeyW(hKey, null, null, null,
                null, null, null, lpcValues, lpcMaxValueNameLen,
                lpcMaxValueLen, null, null);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new Win32Exception(rc);
        }
        TreeMap<String, Object> keyValues = new TreeMap<>();
        // Allocate enough memory to hold largest value and two
        // terminating WCHARs -- the memory is zeroed so after
        // value request we should not overread when reading strings
        try (var arena = Arena.ofConfined()) {

            for (int i = 0; i < lpcValues.value(); i = i + 1) {
                MemorySegment name = arena.allocate(2048);
                var byteData = arena.allocate(lpcMaxValueLen.value());
                IntRef lpcchValueName = new IntRef(lpcMaxValueNameLen.value() + 1);
                IntRef lpcbData = new IntRef(lpcMaxValueLen.value());
                IntRef lpType = new IntRef();
                rc = ADVAPI32.RegEnumValueW(hKey, i, name, lpcchValueName, null, lpType, byteData, lpcbData);
                if (rc != WinError.ERROR_SUCCESS) {
                    throw new Win32Exception(rc);
                }

                String nameString = name.getString(0,StandardCharsets.UTF_16LE);

                if (lpcbData.value() == 0) {
                    switch (lpType.value()) {
                        case REG_BINARY -> keyValues.put(nameString, new byte[0]);
                        case REG_SZ, REG_EXPAND_SZ -> keyValues.put(nameString, new char[0]);
                        case REG_MULTI_SZ -> keyValues.put(nameString, new String[0]);
                        case REG_NONE -> keyValues.put(nameString, null);
                        default -> throw new RuntimeException("Unsupported empty type: " + lpType.value());
                    }
                    continue;
                }

                switch (lpType.value()) {
                    case REG_QWORD -> keyValues.put(nameString, byteData.get(ValueLayout.JAVA_LONG, 0));
                    case REG_DWORD -> keyValues.put(nameString, byteData.get(ValueLayout.JAVA_INT, 0));
                    case REG_SZ, REG_EXPAND_SZ ->
                            keyValues.put(nameString, byteData.getString(0, StandardCharsets.UTF_16LE));
                    case REG_BINARY ->
                            keyValues.put(nameString, Arrays.copyOf(byteData.toArray(ValueLayout.JAVA_BYTE), lpcbData.value()));
                    case REG_MULTI_SZ -> keyValues.put(nameString, null);
                    default -> throw new RuntimeException("Unsupported type: " + lpType.value());
                }
            }
        }

        return keyValues;
    }


    public static int registryGetIntValue(int root, String key, String value) {
        return registryGetIntValue(root, key, value, 0);
    }

    public static int registryGetIntValue(int root, String key, String value, int samDesiredExtra) {
        IntRef phkKey = new IntRef();
        int rc = ADVAPI32.RegOpenKeyExA(root, key, 0, WinNT.KEY_READ | samDesiredExtra, phkKey);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new Win32Exception(rc);
        }
        try {
            return registryGetIntValue(phkKey.value(), value);
        } finally {
            rc = ADVAPI32.RegCloseKey(phkKey.value());
            if (rc != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(rc);
            }
        }
    }

    public static int registryGetIntValue(int hKey, String value) {
        IntRef lpcbData = new IntRef();
        IntRef lpType = new IntRef();
        int rc = ADVAPI32.RegQueryValueExA(hKey, value, 0, lpType, (IntRef) null, lpcbData);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new Win32Exception(rc);
        }
        if (lpType.value() != WinNT.REG_DWORD) {
            throw new RuntimeException("Unexpected registry type "
                    + lpType.value() + ", expected REG_DWORD");
        }
        IntRef data = new IntRef();
        rc = ADVAPI32.RegQueryValueExA(hKey, value, 0, lpType, data, lpcbData);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new Win32Exception(rc);
        }
        return data.value();
    }


    public static void registrySetStringValue(int root, String keyPath,
                                              String name, String value) {
        registrySetStringValue(root, keyPath, name, value, 0);
    }

    public static void registrySetStringValue(int root, String keyPath,
                                              String name, String value, int samDesiredExtra) {
        IntRef phkKey = new IntRef();
        int rc = ADVAPI32.RegOpenKeyExA(root, keyPath, 0, WinNT.KEY_READ | WinNT.KEY_WRITE | samDesiredExtra, phkKey);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new Win32Exception(rc);
        }
        try {
            registrySetStringValue(phkKey.value(), name, value);
        } finally {
            rc = ADVAPI32.RegCloseKey(phkKey.value());
            if (rc != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(rc);
            }
        }
    }

    public static void registrySetStringValue(int hKey, String name,
                                              String value) {
        if (value == null) {
            value = "";
        }
        try (Arena arena = Arena.ofConfined()) {
            var data = arena.allocateFrom(value);
            int rc = ADVAPI32.RegSetValueExA(hKey, name, 0, REG_SZ, data, (int) data.byteSize());
            if (rc != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(rc);
            }
        }

    }


    public static void registrySetIntValue(int root, String keyPath,
                                           String name, int value) {
        registrySetIntValue(root, keyPath, name, value, 0);
    }

    public static void registrySetIntValue(int root, String keyPath,
                                           String name, int value, int samDesiredExtra) {
        IntRef phkKey = new IntRef();
        int rc = ADVAPI32.RegOpenKeyExA(root, keyPath, 0, WinNT.KEY_READ | WinNT.KEY_WRITE | samDesiredExtra, phkKey);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new Win32Exception(rc);
        }
        try {
            registrySetIntValue(phkKey.value(), name, value);
        } finally {
            rc = ADVAPI32.RegCloseKey(phkKey.value());
            if (rc != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(rc);
            }
        }
    }


    public static void registrySetIntValue(int hKey, String name, int value) {
        try (Arena arena = Arena.ofConfined()) {
            var data = arena.allocateFrom(ValueLayout.JAVA_INT, value);
            int rc = ADVAPI32.RegSetValueExA(hKey, name, 0, WinNT.REG_DWORD, data, 4);
            if (rc != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(rc);
            }
        }
    }

    public static String registryGetStringValue(int root, String key,
                                                String value) {
        return registryGetStringValue(root, key, value, 0);
    }

    public static String registryGetStringValue(int root, String key, String value, int samDesiredExtra) {
        IntRef phkKey = new IntRef();
        int rc = ADVAPI32.RegOpenKeyExA(root, key, 0, WinNT.KEY_READ | samDesiredExtra, phkKey);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new Win32Exception(rc);
        }
        try {
            return registryGetStringValue(phkKey.value(), value);
        } finally {
            rc = ADVAPI32.RegCloseKey(phkKey.value());
            if (rc != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(rc);
            }
        }
    }

    public static String registryGetStringValue(int hKey, String value) {
        IntRef lpcbData = new IntRef();
        IntRef lpType = new IntRef();
        int rc = ADVAPI32.RegQueryValueExA(hKey, value, 0, lpType, (MemorySegment) null, lpcbData);
        if (rc != WinError.ERROR_SUCCESS) {
            throw new Win32Exception(rc);
        }
        if (lpType.value() != REG_SZ
                && lpType.value() != WinNT.REG_EXPAND_SZ) {
            throw new RuntimeException("Unexpected registry type "
                    + lpType.value()
                    + ", expected REG_SZ or REG_EXPAND_SZ");
        }
        if (lpcbData.value() == 0) {
            return "";
        }
        try (Arena arena = Arena.ofConfined()) {
            var mem = arena.allocate(lpcbData.value());
            rc = ADVAPI32.RegQueryValueExA(hKey, value, 0, lpType, mem, lpcbData);
            if (rc != WinError.ERROR_SUCCESS) {
                throw new Win32Exception(rc);
            }
            return mem.getString(0);
        }
    }

}
